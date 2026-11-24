package dev.shutchat.app.domain.model

/** What an invite code is for. */
enum class InviteKind {
    /** One-to-one conversation. */
    DIRECT,

    /** Group conversation: anyone holding the code can be added to the roster. */
    GROUP,
    ;

    val wireValue: Int get() = if (this == DIRECT) 1 else 2

    companion object {
        fun fromWireValue(value: Int): InviteKind? = when (value) {
            1 -> DIRECT
            2 -> GROUP
            else -> null
        }
    }
}

/**
 * Decoded contents of an invite code.
 *
 * The expiry travels *inside* the code, so every participant enforces the same
 * deadline. Nobody can extend a code's life by editing their local database, and
 * the creator cannot revoke a code early either — the trade-off of having no
 * server to ask.
 */
data class InvitePayload(
    val version: Int,
    val kind: InviteKind,
    /** Day (UTC, days since 2024-01-01) after which the code stops working; null = never. */
    val expiryDay: Int?,
    /** Rendezvous secret. Peers derive the room id and transport keys from it. */
    val secretHex: String,
) {
    val isPermanent: Boolean get() = expiryDay == null
}

/** A code plus its canonical textual representation. */
data class InviteCode(
    val text: String,
    val payload: InvitePayload,
    /** Stable room identifier derived from the secret. */
    val roomId: String,
) {
    val kind: InviteKind get() = payload.kind
}

/** Thrown when a code cannot be parsed. The message is user facing (Italian). */
class InvalidInviteCodeException(message: String) : IllegalArgumentException(message)
