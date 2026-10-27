package dev.shutchat.app.data.repo

import dev.shutchat.app.data.db.AttachmentEntity
import dev.shutchat.app.data.db.ChatEntity
import dev.shutchat.app.data.db.EnumMapping
import dev.shutchat.app.data.db.MemberEntity
import dev.shutchat.app.data.db.MessageEntity
import dev.shutchat.app.data.db.PeerEntity
import dev.shutchat.app.data.db.ReactionEntity
import dev.shutchat.app.domain.model.Attachment
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatKind
import dev.shutchat.app.domain.model.ChatMember
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.PeerPresence
import dev.shutchat.app.domain.model.Reaction

/**
 * Entity ⇄ domain mapping.
 *
 * Kept out of the repositories so it can be exercised in isolation, and so the
 * wire format never depends on how rows happen to be stored.
 */
object Mappers {

    fun toChat(entity: ChatEntity): Chat = Chat(
        id = entity.id,
        kind = ChatKind.fromWireValue(entity.kind),
        title = entity.title,
        status = EnumMapping.chatStatusFromInt(entity.status),
        inviteCode = entity.inviteCode,
        inviteExpiryDay = entity.inviteExpiryDay,
        createdAt = entity.createdAt,
        lastActivityAt = entity.lastActivityAt,
        pinned = entity.pinned,
        muted = entity.muted,
        unreadCount = entity.unreadCount,
        lastMessagePreview = entity.lastMessagePreview,
        lastMessageSender = entity.lastMessageSender,
        lastMessageAt = entity.lastMessageAt,
    )

    fun toEntity(
        chat: Chat,
        roomId: String,
        inviteSecret: String,
        draft: String = "",
    ): ChatEntity = ChatEntity(
        id = chat.id,
        kind = chat.kind.wireValue,
        title = chat.title,
        status = EnumMapping.chatStatusToInt(chat.status),
        inviteCode = chat.inviteCode,
        inviteExpiryDay = chat.inviteExpiryDay,
        roomId = roomId,
        inviteSecret = inviteSecret,
        createdAt = chat.createdAt,
        lastActivityAt = chat.lastActivityAt,
        pinned = chat.pinned,
        muted = chat.muted,
        unreadCount = chat.unreadCount,
        lastMessagePreview = chat.lastMessagePreview,
        lastMessageSender = chat.lastMessageSender,
        lastMessageAt = chat.lastMessageAt,
        draft = draft,
    )

    fun toMember(entity: MemberEntity): ChatMember = ChatMember(
        chatId = entity.chatId,
        nodeId = entity.nodeId,
        displayName = entity.displayName,
        avatarSeed = entity.avatarSeed,
        isSelf = entity.isSelf,
        isOwner = entity.isOwner,
        joinedAt = entity.joinedAt,
        lastSeenAt = entity.lastSeenAt,
        online = entity.online,
    )

    fun toMessage(
        entity: MessageEntity,
        attachment: AttachmentEntity?,
        reactions: List<Reaction>,
    ): Message = Message(
        id = entity.id,
        chatId = entity.chatId,
        senderNodeId = entity.senderNodeId,
        senderName = entity.senderName,
        kind = MessageKind.fromName(entity.kind),
        body = entity.body,
        createdAt = entity.createdAt,
        status = EnumMapping.messageStatusFromInt(entity.status),
        editedAt = entity.editedAt,
        deleted = entity.deleted,
        replyToId = entity.replyToId,
        replyToPreview = entity.replyToPreview,
        attachment = attachment?.let(::toAttachment),
        reactions = reactions,
        hops = entity.hops,
        outgoing = entity.outgoing,
    )

    fun toAttachment(entity: AttachmentEntity): Attachment = Attachment(
        id = entity.id,
        messageId = entity.messageId,
        fileName = entity.fileName,
        mimeType = entity.mimeType,
        sizeBytes = entity.sizeBytes,
        localPath = entity.localPath,
        progress = entity.progress,
        durationMillis = entity.durationMillis,
        width = entity.width,
        height = entity.height,
    )

    fun toReaction(entity: ReactionEntity): Reaction = Reaction(
        messageId = entity.messageId,
        nodeId = entity.nodeId,
        emoji = entity.emoji,
    )

    fun toPresence(entity: PeerEntity, roomIds: Set<String>): PeerPresence = PeerPresence(
        nodeId = entity.nodeId,
        displayName = entity.displayName,
        avatarSeed = entity.avatarSeed,
        transport = entity.transport,
        roomIds = roomIds,
        lastSeenAt = entity.lastSeenAt,
        online = false,
        safetyNumber = entity.safetyNumber,
    )
}
