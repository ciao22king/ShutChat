package dev.shutchat.app.data.repo

import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Log
import dev.shutchat.app.core.toHex
import dev.shutchat.app.data.db.AttachmentEntity
import dev.shutchat.app.data.db.ChatEntity
import dev.shutchat.app.data.db.EnumMapping
import dev.shutchat.app.data.db.MemberEntity
import dev.shutchat.app.data.db.MessageEntity
import dev.shutchat.app.data.db.PeerEntity
import dev.shutchat.app.data.db.ReactionEntity
import dev.shutchat.app.data.db.ShutChatDatabase
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.domain.model.Attachment
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatMember
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.domain.model.PeerPresence
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.PeerInfo
import dev.shutchat.app.p2p.ReceivedEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads for the conversation list and for a single conversation.
 *
 * The list is assembled in one pass over the chats table; unread counts and last
 * message previews are denormalised onto the row so opening the app does not fan
 * out into a query per chat.
 */
class ChatRepository(
    private val db: ShutChatDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
    scope: CoroutineScope,
) {

    val chats: Flow<List<Chat>> = db.chatDao().observeActive()
        .map { rows -> rows.map(Mappers::toChat) }

    val archived: Flow<List<Chat>> = db.chatDao().observeArchived()
        .map { rows -> rows.map(Mappers::toChat) }

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    fun observeChat(chatId: String): Flow<Chat?> =
        db.chatDao().observe(chatId).map { it?.let(Mappers::toChat) }

    fun observeMembers(chatId: String): Flow<List<ChatMember>> =
        db.memberDao().observe(chatId).map { rows -> rows.map(Mappers::toMember) }

    suspend fun chat(chatId: String): Chat? = db.chatDao().find(chatId)?.let(Mappers::toChat)

    suspend fun chatEntity(chatId: String): ChatEntity? = db.chatDao().find(chatId)

    /** Resolves a room id back to its chat row, for envelopes that name a room. */
    suspend fun chatEntityForRoom(roomId: String): ChatEntity? = db.chatDao().findByRoom(roomId)

    suspend fun members(chatId: String): List<ChatMember> =
        db.memberDao().list(chatId).map(Mappers::toMember)

    suspend fun setTitle(chatId: String, title: String) {
        val entity = db.chatDao().find(chatId) ?: return
        db.chatDao().upsert(entity.copy(title = title))
    }

    suspend fun setPinned(chatId: String, pinned: Boolean) = db.chatDao().setPinned(chatId, pinned)

    suspend fun setMuted(chatId: String, muted: Boolean) = db.chatDao().setMuted(chatId, muted)

    suspend fun setArchived(chatId: String, archived: Boolean) {
        db.chatDao().setStatus(
            chatId,
            EnumMapping.chatStatusToInt(if (archived) ChatStatus.ARCHIVED else ChatStatus.ACTIVE),
        )
    }

    suspend fun markRead(chatId: String) = db.chatDao().clearUnread(chatId)

    /** Moves a chat to the top of the list after a local send. */
    suspend fun touch(chatId: String, at: Long) {
        val entity = db.chatDao().find(chatId) ?: return
        db.chatDao().updateLastMessage(
            chatId = chatId,
            preview = entity.lastMessagePreview.orEmpty(),
            sender = entity.lastMessageSender.orEmpty(),
            at = at,
        )
    }

    suspend fun saveDraft(chatId: String, draft: String) = db.chatDao().setDraft(chatId, draft)

    suspend fun draft(chatId: String): String = db.chatDao().find(chatId)?.draft ?: ""

    suspend fun addMember(chatId: String, nodeId: String, displayName: String, isOwner: Boolean = false) {
        val existing = db.memberDao().find(chatId, nodeId)
        if (existing != null) return
        db.memberDao().upsert(
            MemberEntity(
                chatId = chatId,
                nodeId = nodeId,
                displayName = displayName,
                avatarSeed = nodeId,
                isSelf = false,
                isOwner = isOwner,
                joinedAt = clock.nowMillis(),
                lastSeenAt = clock.nowMillis(),
            ),
        )
    }

    suspend fun removeMember(chatId: String, nodeId: String) = db.memberDao().delete(chatId, nodeId)

    suspend fun updatePeerPresence(peers: List<PeerInfo>) {
        _peers.value = peers
        val now = clock.nowMillis()
        db.memberDao().markAllOffline()
        peers.forEach { peer ->
            db.memberDao().setOnline(peer.nodeId, online = true, at = now)
            db.peerDao().upsert(
                PeerEntity(
                    nodeId = peer.nodeId,
                    displayName = peer.displayName,
                    avatarSeed = peer.nodeId,
                    lastSeenAt = now,
                    transport = peer.transport,
                    safetyNumber = null,
                ),
            )
        }
    }

    /** Removes transcripts older than the retention window from active chats. */
    suspend fun purgeOldMessages() {
        val days = settings.retentionDays.first()
        if (days <= 0) return
        val cutoff = clock.nowMillis() - days * 24L * 60 * 60 * 1000
        val removed = db.messageDao().purgeOlderThan(cutoff)
        if (removed > 0) Log.i(TAG, "rimossi $removed messaggi oltre la finestra di $days giorni")
    }

    private companion object {
        const val TAG = "ChatRepository"
    }
}

/**
 * Reads for a single conversation: the transcript, and the write path for new
 * messages, edits, reactions and receipts.
 */
class MessageRepository(
    private val db: ShutChatDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val mesh: MeshEngine,
    private val scope: CoroutineScope,
) {

    fun observeMessages(chatId: String): Flow<List<Message>> =
        db.messageDao().observeWithRelations(chatId).map { rows ->
            rows.map { row ->
                Mappers.toMessage(
                    entity = row.message,
                    attachment = row.attachments.firstOrNull(),
                    reactions = row.reactions.map(Mappers::toReaction),
                )
            }
        }

    suspend fun search(chatId: String, query: String): List<Message> =
        db.messageDao().search(chatId, query).map { Mappers.toMessage(it, null, emptyList()) }

    suspend fun message(id: String): Message? =
        db.messageDao().findWithRelations(id)?.let {
            Mappers.toMessage(it.message, it.attachments.firstOrNull(), it.reactions.map(Mappers::toReaction))
        }

    suspend fun count(): Int = db.messageDao().count()

    /** Persists a locally composed message as PENDING before it hits the wire. */
    suspend fun storeOutgoing(message: Message) {
        db.messageDao().upsert(
            MessageEntity(
                id = message.id,
                chatId = message.chatId,
                senderNodeId = message.senderNodeId,
                senderName = message.senderName,
                kind = message.kind.name,
                body = message.body,
                createdAt = message.createdAt,
                status = EnumMapping.messageStatusToInt(message.status),
                outgoing = true,
                editedAt = null,
                deleted = false,
                replyToId = message.replyToId,
                replyToPreview = message.replyToPreview,
                hops = 0,
            ),
        )
    }

    suspend fun storeAttachment(attachment: Attachment) {
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = attachment.id,
                messageId = attachment.messageId,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                localPath = attachment.localPath,
                progress = attachment.progress,
                durationMillis = attachment.durationMillis,
                width = attachment.width,
                height = attachment.height,
            ),
        )
    }

    /** Persists a message that arrived from a peer. */
    suspend fun storeIncoming(message: Message, senderNodeId: String) {
        if (db.messageDao().find(message.id) != null) return
        db.messageDao().upsert(
            MessageEntity(
                id = message.id,
                chatId = message.chatId,
                senderNodeId = senderNodeId,
                senderName = message.senderName,
                kind = message.kind.name,
                body = message.body,
                createdAt = message.createdAt,
                status = EnumMapping.messageStatusToInt(MessageStatus.DELIVERED),
                outgoing = false,
                editedAt = null,
                deleted = false,
                replyToId = message.replyToId,
                replyToPreview = message.replyToPreview,
                hops = message.hops,
            ),
        )
        message.attachment?.let { storeAttachment(it) }
    }

    suspend fun setStatus(ids: List<String>, status: MessageStatus) {
        if (ids.isEmpty()) return
        db.messageDao().setStatus(ids, EnumMapping.messageStatusToInt(status))
    }

    suspend fun edit(messageId: String, newBody: String) {
        val at = clock.nowMillis()
        db.messageDao().setBody(messageId, newBody, at)
        val entity = db.messageDao().find(messageId) ?: return
        val chat = db.chatDao().find(entity.chatId) ?: return
        mesh.broadcast(
            dev.shutchat.app.p2p.protocol.EditMessage(
                roomId = chat.roomId,
                messageId = messageId,
                nodeId = settings.nodeId(),
                newBody = newBody,
                editedAt = at,
            ),
        )
    }

    suspend fun delete(messageId: String) {
        val entity = db.messageDao().find(messageId) ?: return
        db.messageDao().softDelete(messageId)
        db.reactionDao().removeAll(messageId)
        val chat = db.chatDao().find(entity.chatId) ?: return
        mesh.broadcast(
            dev.shutchat.app.p2p.protocol.DeleteMessage(
                roomId = chat.roomId,
                messageId = messageId,
                nodeId = settings.nodeId(),
            ),
        )
    }

    suspend fun react(messageId: String, emoji: String, removed: Boolean) {
        val nodeId = settings.nodeId()
        if (removed) {
            db.reactionDao().remove(messageId, nodeId)
        } else {
            db.reactionDao().upsert(ReactionEntity(messageId, nodeId, emoji))
        }
        val entity = db.messageDao().find(messageId) ?: return
        val chat = db.chatDao().find(entity.chatId) ?: return
        mesh.broadcast(
            dev.shutchat.app.p2p.protocol.ReactionUpdate(
                roomId = chat.roomId,
                messageId = messageId,
                nodeId = nodeId,
                emoji = emoji,
                removed = removed,
            ),
        )
    }

    suspend fun applyRemoteEdit(messageId: String, newBody: String, editedAt: Long) {
        db.messageDao().setBody(messageId, newBody, editedAt)
    }

    suspend fun applyRemoteDelete(messageId: String) {
        db.messageDao().softDelete(messageId)
        db.reactionDao().removeAll(messageId)
    }

    suspend fun applyRemoteReaction(messageId: String, nodeId: String, emoji: String, removed: Boolean) {
        if (removed) {
            db.reactionDao().remove(messageId, nodeId)
        } else {
            db.reactionDao().upsert(ReactionEntity(messageId, nodeId, emoji))
        }
    }

    /**
     * Tells the room that these messages arrived, or were read.
     *
     * Broadcast rather than addressed to one node: a group has several senders and
     * every one of them needs the acknowledgement, and the envelope is sealed with
     * the room key either way.
     */
    suspend fun acknowledge(roomId: String, messageIds: List<String>, state: String) {
        if (messageIds.isEmpty()) return
        mesh.broadcast(
            dev.shutchat.app.p2p.protocol.Receipt(
                roomId = roomId,
                messageIds = messageIds,
                state = state,
                nodeId = settings.nodeId(),
            ),
        )
    }

    /**
     * Marks the incoming messages of a chat as read and tells the room.
     *
     * Sent as a batch of recent ids rather than the whole transcript: a receipt
     * names messages explicitly, and re-acknowledging history on every open would
     * grow the envelope without telling anyone anything new.
     */
    suspend fun acknowledgeRead(chatId: String) {
        // Delivery receipts are a transport fact and are always sent, because the
        // sender's outbox needs them to stop retrying. Read receipts are a privacy
        // choice the user makes in settings, and only they are gated here.
        if (!settings.readReceipts.first()) return
        val chat = db.chatDao().find(chatId) ?: return
        val ids = db.messageDao().recentIncomingIds(chatId, limit = READ_RECEIPT_LIMIT)
        if (ids.isEmpty()) return
        acknowledge(chat.roomId, ids, state = "read")
    }

    /** Queued messages to retry when a peer becomes reachable again. */
    suspend fun pending(): List<Message> =
        db.messageDao().pendingOutgoing().map { Mappers.toMessage(it, null, emptyList()) }

    private companion object {
        /** How many recent incoming messages a read receipt names. */
        const val READ_RECEIPT_LIMIT = 50
    }

    suspend fun updateAttachmentProgress(attachmentId: String, progress: Float) =
        db.attachmentDao().setProgress(attachmentId, progress)

    suspend fun completeAttachment(attachmentId: String, localPath: String) =
        db.attachmentDao().setLocalPath(attachmentId, localPath)
}

/** Turns an incoming [ReceivedEnvelope] into local rows. */
class InboundHandler(
    private val db: ShutChatDatabase,
    private val chats: ChatRepository,
    private val messages: MessageRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /**
     * @return the chat id the envelope belonged to, or null when it was dropped.
     */
    suspend fun handle(envelope: ReceivedEnvelope): String? {
        val roomId = envelope.envelope.roomId
        val chat = db.chatDao().findByRoom(roomId) ?: return null
        val sender = envelope.envelope

        when (sender) {
            is dev.shutchat.app.p2p.protocol.WireMessage -> {
                val existing = db.messageDao().find(sender.messageId)
                if (existing != null) {
                    // Already stored, so this is a re-send. Acknowledge it again
                    // rather than returning silently: the sender is retrying
                    // precisely because it never saw the earlier receipt, and
                    // staying quiet would keep it retrying for the whole window.
                    messages.acknowledge(chat.roomId, listOf(sender.messageId), state = "delivered")
                    return chat.id
                }
                val message = Message(
                    id = sender.messageId,
                    chatId = chat.id,
                    senderNodeId = sender.senderNodeId,
                    senderName = sender.senderName,
                    kind = MessageKind.fromName(sender.kind),
                    body = sender.body,
                    createdAt = sender.createdAt,
                    status = MessageStatus.DELIVERED,
                    replyToId = sender.replyToId,
                    replyToPreview = sender.replyToPreview,
                    attachment = sender.attachment?.let {
                        Attachment(
                            id = it.id,
                            messageId = sender.messageId,
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                            localPath = null,
                            progress = 0f,
                            durationMillis = it.durationMillis,
                            width = it.width,
                            height = it.height,
                        )
                    },
                    hops = sender.hops,
                    outgoing = false,
                )
                messages.storeIncoming(message, sender.senderNodeId)
                chats.addMember(chat.id, sender.senderNodeId, sender.senderName)
                // Tell the sender it landed. Without this the sender's outbox
                // cannot know the message arrived, so it would re-send it forever,
                // and "delivered" would be a claim nothing had verified.
                messages.acknowledge(chat.roomId, listOf(sender.messageId), state = "delivered")
                db.chatDao().updateLastMessage(
                    chatId = chat.id,
                    preview = dev.shutchat.app.core.Format.messagePreview(sender.body, sender.kind),
                    sender = sender.senderName,
                    at = sender.createdAt,
                )
                db.chatDao().bumpUnread(chat.id)
            }

            is dev.shutchat.app.p2p.protocol.EditMessage ->
                messages.applyRemoteEdit(sender.messageId, sender.newBody, sender.editedAt)

            is dev.shutchat.app.p2p.protocol.DeleteMessage ->
                messages.applyRemoteDelete(sender.messageId)

            is dev.shutchat.app.p2p.protocol.ReactionUpdate ->
                messages.applyRemoteReaction(sender.messageId, sender.nodeId, sender.emoji, sender.removed)

            is dev.shutchat.app.p2p.protocol.JoinNotice -> {
                chats.addMember(chat.id, sender.nodeId, sender.displayName)
                db.chatDao().updateLastMessage(
                    chatId = chat.id,
                    preview = "${sender.displayName} è entrato",
                    sender = "",
                    at = clock.nowMillis(),
                )
            }

            is dev.shutchat.app.p2p.protocol.LeaveNotice ->
                chats.removeMember(chat.id, sender.nodeId)

            is dev.shutchat.app.p2p.protocol.MemberRoster -> {
                sender.members.forEach { member ->
                    chats.addMember(chat.id, member.nodeId, member.displayName, member.isOwner)
                }
            }

            is dev.shutchat.app.p2p.protocol.Receipt -> {
                val status = if (sender.state == "read") MessageStatus.READ else MessageStatus.DELIVERED
                messages.setStatus(sender.messageIds, status)
            }

            is dev.shutchat.app.p2p.protocol.Hello ->
                chats.addMember(chat.id, sender.nodeId, sender.displayName)

            is dev.shutchat.app.p2p.protocol.HelloAck ->
                chats.addMember(chat.id, sender.nodeId, sender.displayName)

            else -> Unit
        }
        return chat.id
    }
}
