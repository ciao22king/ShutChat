package dev.shutchat.app.domain.usecase

import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.Ids
import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.data.db.ChatEntity
import dev.shutchat.app.data.db.EnumMapping
import dev.shutchat.app.data.db.MemberEntity
import dev.shutchat.app.data.db.ShutChatDatabase
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.domain.codec.InviteCodec
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.ChatKind
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.InviteCode
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.p2p.RoomSpec
import kotlinx.coroutines.flow.first

/**
 * Creating and joining conversations from invite codes.
 *
 * This is the piece the "+" button drives, and it is deliberately the only place
 * that knows how a code turns into a row in the database.
 */
class ChatFactory(
    private val db: ShutChatDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /** Result of a create or join, so the UI can navigate to the new chat. */
    data class Outcome(
        val chatId: String,
        val created: Boolean,
        val inviteCode: String,
    )

    /**
     * Creates a conversation and returns the code to share.
     *
     * @param kind direct or group; the code itself carries the kind, so a peer
     *        joining with it lands in the right shape of conversation.
     */
    suspend fun create(
        kind: InviteKind,
        expiryDay: Int?,
        title: String?,
    ): Outcome {
        val nodeId = settings.nodeId()
        val displayName = settings.currentDisplayName().ifBlank { "Tu" }
        val now = clock.nowMillis()

        val code = InviteCodec.create(kind = kind, expiryDay = expiryDay)
        val secret = InviteCodec.secretOf(code)
        val chatId = Ids.newId()
        val defaultTitle = when (kind) {
            InviteKind.DIRECT -> "Chat senza nome"
            InviteKind.GROUP -> title?.ifBlank { null } ?: "Gruppo senza nome"
        }

        db.chatDao().upsert(
            ChatEntity(
                id = chatId,
                kind = ChatKind.fromInviteKind(kind).wireValue,
                title = defaultTitle,
                status = EnumMapping.chatStatusToInt(ChatStatus.ACTIVE),
                inviteCode = code.text,
                inviteExpiryDay = expiryDay,
                roomId = code.roomId,
                inviteSecret = code.payload.secretHex,
                createdAt = now,
                lastActivityAt = now,
                pinned = false,
                muted = false,
                unreadCount = 0,
                lastMessagePreview = null,
                lastMessageSender = null,
                lastMessageAt = null,
            ),
        )
        db.memberDao().upsert(
            MemberEntity(
                chatId = chatId,
                nodeId = nodeId,
                displayName = displayName,
                avatarSeed = nodeId,
                isSelf = true,
                isOwner = true,
                joinedAt = now,
                lastSeenAt = now,
            ),
        )
        return Outcome(chatId = chatId, created = true, inviteCode = code.text)
    }

    /**
     * Joins a conversation from a pasted or scanned code.
     *
     * Joining an existing chat is a no-op that just returns its id, so pasting
     * the same code twice does not create a duplicate conversation.
     */
    suspend fun join(rawCode: String): Outcome {
        val code: InviteCode = InviteCodec.parse(rawCode)
        if (Expiry.isExpired(code.payload.expiryDay, clock)) {
            throw dev.shutchat.app.domain.model.InvalidInviteCodeException(
                "Questo codice è scaduto il " + Expiry.describeDeadline(code.payload.expiryDay, clock),
            )
        }

        db.chatDao().findByRoom(code.roomId)?.let { existing ->
            // Re-joining is how a user recovers a chat they archived.
            if (existing.status == EnumMapping.chatStatusToInt(ChatStatus.ARCHIVED)) {
                db.chatDao().setStatus(existing.id, EnumMapping.chatStatusToInt(ChatStatus.ACTIVE))
            }
            return Outcome(existing.id, created = false, inviteCode = existing.inviteCode ?: code.text)
        }

        val nodeId = settings.nodeId()
        val displayName = settings.currentDisplayName().ifBlank { "Tu" }
        val now = clock.nowMillis()
        val chatId = Ids.newId()

        db.chatDao().upsert(
            ChatEntity(
                id = chatId,
                kind = ChatKind.fromInviteKind(code.kind).wireValue,
                title = if (code.kind == InviteKind.GROUP) "Gruppo senza nome" else "Chat senza nome",
                status = EnumMapping.chatStatusToInt(ChatStatus.ACTIVE),
                inviteCode = code.text,
                inviteExpiryDay = code.payload.expiryDay,
                roomId = code.roomId,
                inviteSecret = code.payload.secretHex,
                createdAt = now,
                lastActivityAt = now,
                pinned = false,
                muted = false,
                unreadCount = 0,
                lastMessagePreview = null,
                lastMessageSender = null,
                lastMessageAt = null,
            ),
        )
        db.memberDao().upsert(
            MemberEntity(
                chatId = chatId,
                nodeId = nodeId,
                displayName = displayName,
                avatarSeed = nodeId,
                isSelf = true,
                isOwner = false,
                joinedAt = now,
                lastSeenAt = now,
            ),
        )
        return Outcome(chatId = chatId, created = true, inviteCode = code.text)
    }

    /** Every room this device should advertise, with expiry already applied. */
    suspend fun activeRoomSpecs(): List<RoomSpec> {
        val nodeId = settings.nodeId()
        val displayName = settings.currentDisplayName().ifBlank { "Tu" }
        return db.chatDao().all()
            .filter { it.status != EnumMapping.chatStatusToInt(ChatStatus.ARCHIVED) }
            .map { entity ->
                RoomSpec(
                    chatId = entity.id,
                    roomIdHex = entity.roomId,
                    inviteSecret = entity.inviteSecret.hexToBytes(),
                    displayName = displayName,
                    nodeId = nodeId,
                    expired = Expiry.isExpired(entity.inviteExpiryDay, clock),
                )
            }
    }

    /**
     * Marks chats whose code has passed its deadline.
     *
     * Runs on every app start and whenever the day rolls over. The transcript is
     * kept: only the ability to send new traffic for that room goes away.
     *
     * @return ids of the chats that just expired, so the UI can notify.
     */
    suspend fun expireOutdatedChats(): List<String> {
        val now = clock.nowMillis()
        val newlyExpired = mutableListOf<String>()
        db.chatDao().withExpiry().forEach { entity ->
            val expired = Expiry.isExpired(entity.inviteExpiryDay, clock)
            val current = EnumMapping.chatStatusFromInt(entity.status)
            when {
                expired && current == ChatStatus.ACTIVE -> {
                    db.chatDao().setStatus(entity.id, EnumMapping.chatStatusToInt(ChatStatus.EXPIRED))
                    newlyExpired += entity.id
                }

                !expired && current == ChatStatus.EXPIRED -> {
                    // Clock skew can resurrect a code; keep state consistent.
                    db.chatDao().setStatus(entity.id, EnumMapping.chatStatusToInt(ChatStatus.ACTIVE))
                }
            }
        }
        if (newlyExpired.isNotEmpty()) {
            settings.setLastExpirySweep(now)
        }
        return newlyExpired
    }

    /** Safety number for a chat, for out-of-band verification. */
    fun safetyNumber(inviteSecretHex: String): String =
        RoomCrypto.safetyNumber(inviteSecretHex.hexToBytes())

    /** Regenerates the code for a chat, keeping the transcript. */
    suspend fun rotateCode(chatId: String, expiryDay: Int?): InviteCode {
        val entity = db.chatDao().find(chatId) ?: error("chat inesistente")
        val kind = if (entity.kind == ChatKind.GROUP.wireValue) InviteKind.GROUP else InviteKind.DIRECT
        val code = InviteCodec.create(kind = kind, expiryDay = expiryDay)
        db.chatDao().upsert(
            entity.copy(
                inviteCode = code.text,
                inviteExpiryDay = expiryDay,
                roomId = code.roomId,
                inviteSecret = code.payload.secretHex,
                status = EnumMapping.chatStatusToInt(ChatStatus.ACTIVE),
            ),
        )
        return code
    }

    /** Deletes a chat and everything in it. */
    suspend fun deleteChat(chatId: String) {
        db.messageDao().deleteForChat(chatId)
        db.chatDao().delete(chatId)
    }
}
