package dev.shutchat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatEntity::class,
        MemberEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ReactionEntity::class,
        PeerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShutChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun memberDao(): MemberDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reactionDao(): ReactionDao
    abstract fun peerDao(): PeerDao

    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val NAME = "shutchat.db"

        fun build(context: Context): ShutChatDatabase =
            Room.databaseBuilder(context, ShutChatDatabase::class.java, NAME)
                // Everything is local-only and rebuildable from peers, so a
                // schema change can drop and recreate rather than migrate.
                .fallbackToDestructiveMigration()
                .build()
    }
}

/**
 * Enum columns are stored as small ints. Keeping the mapping in one place stops
 * the UI, DAOs and wire format from drifting apart.
 */
object EnumMapping {
    fun chatStatusToInt(status: dev.shutchat.app.domain.model.ChatStatus): Int = when (status) {
        dev.shutchat.app.domain.model.ChatStatus.ACTIVE -> 0
        dev.shutchat.app.domain.model.ChatStatus.EXPIRED -> 1
        dev.shutchat.app.domain.model.ChatStatus.ARCHIVED -> 2
    }

    fun chatStatusFromInt(value: Int): dev.shutchat.app.domain.model.ChatStatus = when (value) {
        1 -> dev.shutchat.app.domain.model.ChatStatus.EXPIRED
        2 -> dev.shutchat.app.domain.model.ChatStatus.ARCHIVED
        else -> dev.shutchat.app.domain.model.ChatStatus.ACTIVE
    }

    fun messageStatusToInt(status: dev.shutchat.app.domain.model.MessageStatus): Int = when (status) {
        dev.shutchat.app.domain.model.MessageStatus.PENDING -> 0
        dev.shutchat.app.domain.model.MessageStatus.SENDING -> 1
        dev.shutchat.app.domain.model.MessageStatus.QUEUED -> 2
        dev.shutchat.app.domain.model.MessageStatus.DELIVERED -> 3
        dev.shutchat.app.domain.model.MessageStatus.READ -> 4
        dev.shutchat.app.domain.model.MessageStatus.FAILED -> 5
    }

    fun messageStatusFromInt(value: Int): dev.shutchat.app.domain.model.MessageStatus = when (value) {
        0 -> dev.shutchat.app.domain.model.MessageStatus.PENDING
        1 -> dev.shutchat.app.domain.model.MessageStatus.SENDING
        2 -> dev.shutchat.app.domain.model.MessageStatus.QUEUED
        3 -> dev.shutchat.app.domain.model.MessageStatus.DELIVERED
        4 -> dev.shutchat.app.domain.model.MessageStatus.READ
        else -> dev.shutchat.app.domain.model.MessageStatus.FAILED
    }
}
