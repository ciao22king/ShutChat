package dev.shutchat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chats",
    indices = [Index(value = ["room_id"], unique = true)],
)
data class ChatEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "invite_code") val inviteCode: String?,
    @ColumnInfo(name = "invite_expiry_day") val inviteExpiryDay: Int?,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "invite_secret") val inviteSecret: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_activity_at") val lastActivityAt: Long,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "muted") val muted: Boolean,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "last_message_sender") val lastMessageSender: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
    @ColumnInfo(name = "draft") val draft: String = "",
)

@Entity(
    tableName = "members",
    primaryKeys = ["chat_id", "node_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chat_id"), Index("node_id")],
)
data class MemberEntity(
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_seed") val avatarSeed: String,
    @ColumnInfo(name = "is_self") val isSelf: Boolean,
    @ColumnInfo(name = "is_owner") val isOwner: Boolean,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "online") val online: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chat_id"),
        Index(value = ["chat_id", "created_at"]),
        Index("status"),
    ],
)
data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "sender_node_id") val senderNodeId: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    @ColumnInfo(name = "reply_to_id") val replyToId: String?,
    @ColumnInfo(name = "reply_to_preview") val replyToPreview: String?,
    @ColumnInfo(name = "hops") val hops: Int,
)

@Entity(
    tableName = "attachments",
    indices = [Index("message_id")],
)
data class AttachmentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "progress") val progress: Float,
    @ColumnInfo(name = "duration_millis") val durationMillis: Long?,
    @ColumnInfo(name = "width") val width: Int?,
    @ColumnInfo(name = "height") val height: Int?,
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["message_id", "node_id"],
    indices = [Index("message_id")],
)
data class ReactionEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "emoji") val emoji: String,
)

/** Peers seen recently, so names resolve even before a fresh handshake. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_seed") val avatarSeed: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "transport") val transport: String,
    @ColumnInfo(name = "safety_number") val safetyNumber: String?,
)
