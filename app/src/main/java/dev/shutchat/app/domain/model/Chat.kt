package dev.shutchat.app.domain.model

/** Direct conversation or group. Mirrors [InviteKind] but is stored per chat. */
enum class ChatKind {
    DIRECT,
    GROUP,
    ;

    val wireValue: Int get() = if (this == DIRECT) 1 else 2

    companion object {
        fun fromWireValue(value: Int): ChatKind = if (value == 2) GROUP else DIRECT

        fun fromInviteKind(kind: InviteKind): ChatKind =
            if (kind == InviteKind.GROUP) GROUP else DIRECT
    }
}

/**
 * Lifecycle of a conversation.
 *
 * [EXPIRED] means the invite code that created it passed its deadline. The
 * transcript is kept but no peer will accept new traffic for that room.
 */
enum class ChatStatus {
    ACTIVE,
    EXPIRED,
    ARCHIVED,
}

data class Chat(
    val id: String,
    val kind: ChatKind,
    val title: String,
    val status: ChatStatus,
    /** 24 character code that opens this conversation, kept for re-sharing. */
    val inviteCode: String?,
    val inviteExpiryDay: Int?,
    val createdAt: Long,
    val lastActivityAt: Long,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageSender: String? = null,
    val lastMessageAt: Long? = null,
) {
    val isGroup: Boolean get() = kind == ChatKind.GROUP
    val isPermanentInvite: Boolean get() = inviteExpiryDay == null
}

data class ChatMember(
    val chatId: String,
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val isSelf: Boolean,
    val isOwner: Boolean,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val online: Boolean = false,
)
