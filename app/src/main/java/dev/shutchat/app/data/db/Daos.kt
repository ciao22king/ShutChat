package dev.shutchat.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats WHERE status != 2 ORDER BY pinned DESC, last_activity_at DESC")
    fun observeActive(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE status = 2 ORDER BY last_activity_at DESC")
    fun observeArchived(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats ORDER BY pinned DESC, last_activity_at DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observe(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun find(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE room_id = :roomId")
    suspend fun findByRoom(roomId: String): ChatEntity?

    @Query("SELECT * FROM chats")
    suspend fun all(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE invite_expiry_day IS NOT NULL")
    suspend fun withExpiry(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)

    @Query("UPDATE chats SET unread_count = 0 WHERE id = :chatId")
    suspend fun clearUnread(chatId: String)

    @Query("UPDATE chats SET unread_count = unread_count + 1 WHERE id = :chatId AND muted = 0")
    suspend fun bumpUnread(chatId: String)

    @Query("UPDATE chats SET pinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET muted = :muted WHERE id = :chatId")
    suspend fun setMuted(chatId: String, muted: Boolean)

    @Query("UPDATE chats SET status = :status WHERE id = :chatId")
    suspend fun setStatus(chatId: String, status: Int)

    @Query("UPDATE chats SET draft = :draft WHERE id = :chatId")
    suspend fun setDraft(chatId: String, draft: String)

    @Query(
        """
        UPDATE chats SET
            last_message_preview = :preview,
            last_message_sender = :sender,
            last_message_at = :at,
            last_activity_at = :at
        WHERE id = :chatId
        """,
    )
    suspend fun updateLastMessage(chatId: String, preview: String, sender: String, at: Long)

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun count(): Int
}

@Dao
interface MemberDao {

    @Query("SELECT * FROM members WHERE chat_id = :chatId ORDER BY is_self DESC, joined_at ASC")
    fun observe(chatId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE chat_id = :chatId ORDER BY is_self DESC, joined_at ASC")
    suspend fun list(chatId: String): List<MemberEntity>

    @Query("SELECT * FROM members WHERE chat_id = :chatId AND node_id = :nodeId")
    suspend fun find(chatId: String, nodeId: String): MemberEntity?

    @Query("SELECT COUNT(*) FROM members WHERE chat_id = :chatId")
    suspend fun count(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<MemberEntity>)

    @Query("UPDATE members SET online = :online, last_seen_at = :at WHERE node_id = :nodeId")
    suspend fun setOnline(nodeId: String, online: Boolean, at: Long)

    @Query("UPDATE members SET online = 0")
    suspend fun markAllOffline()

    @Query("DELETE FROM members WHERE chat_id = :chatId AND node_id = :nodeId")
    suspend fun delete(chatId: String, nodeId: String)

    /**
     * Empties a chat's roster.
     *
     * The foreign key already cascades, but the cascade only fires when the chat
     * row goes, and doing it explicitly keeps the delete order independent of
     * whether foreign keys happen to be enabled on the connection.
     */
    @Query("DELETE FROM members WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface MessageDao {

    @Transaction
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC, id ASC")
    fun observeWithRelations(chatId: String): Flow<List<MessageWithRelations>>

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findWithRelations(id: String): MessageWithRelations?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC, id ASC")
    fun observe(chatId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE chat_id = :chatId AND (body LIKE '%' || :query || '%' OR sender_name LIKE '%' || :query || '%')
        ORDER BY created_at DESC
        """,
    )
    suspend fun search(chatId: String, query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun find(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun list(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status IN (0, 1, 2) AND outgoing = 1 ORDER BY created_at ASC")
    suspend fun pendingOutgoing(): List<MessageEntity>

    /** Outgoing messages no receipt has arrived for, oldest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE outgoing = 1 AND deleted = 0 AND status IN (0, 1, 2)
        ORDER BY created_at ASC
        """,
    )
    suspend fun undeliveredOutgoing(): List<MessageEntity>

    @Query("SELECT status FROM messages WHERE id = :id")
    suspend fun statusOf(id: String): Int?

    /** Recent incoming ids, for a read receipt that names them. */
    @Query(
        """
        SELECT id FROM messages
        WHERE chat_id = :chatId AND outgoing = 0 AND deleted = 0
        ORDER BY created_at DESC LIMIT :limit
        """,
    )
    suspend fun recentIncomingIds(chatId: String, limit: Int): List<String>

    @Query("SELECT MAX(created_at) FROM messages WHERE chat_id = :chatId")
    suspend fun latestTimestamp(chatId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: Int)

    @Query("UPDATE messages SET status = :status WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<String>, status: Int)

    @Query("UPDATE messages SET body = :body, edited_at = :editedAt WHERE id = :id")
    suspend fun setBody(id: String, body: String, editedAt: Long)

    @Query("UPDATE messages SET deleted = 1, body = '' WHERE id = :id")
    suspend fun softDelete(id: String)

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    /**
     * Every local file a chat's attachments point at.
     *
     * Read before the rows are dropped, because once they are gone there is no
     * way left to find the files and they would sit in private storage forever.
     */
    @Query(
        """
        SELECT local_path FROM attachments
        WHERE local_path IS NOT NULL
          AND message_id IN (SELECT id FROM messages WHERE chat_id = :chatId)
        """,
    )
    suspend fun localPathsForChat(chatId: String): List<String>

    @Query("DELETE FROM messages WHERE created_at < :before AND chat_id IN (SELECT id FROM chats WHERE status = 0)")
    suspend fun purgeOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE message_id = :messageId")
    suspend fun find(messageId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE message_id IN (:messageIds)")
    suspend fun findFor(messageIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE progress < 1")
    suspend fun incomplete(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("UPDATE attachments SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: String, progress: Float)

    @Query("UPDATE attachments SET local_path = :path, progress = 1.0 WHERE id = :id")
    suspend fun setLocalPath(id: String, path: String)

    /**
     * Drops attachment rows belonging to a chat.
     *
     * Needed because `attachments` carries no foreign key to `messages`, so
     * nothing removes these rows when the messages go. Without this a deleted
     * chat leaves rows pointing at files that no longer exist.
     */
    @Query("DELETE FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE chat_id = :chatId)")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface ReactionDao {

    @Query("SELECT * FROM reactions WHERE message_id IN (:messageIds)")
    suspend fun forMessages(messageIds: List<String>): List<ReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE message_id = :messageId AND node_id = :nodeId")
    suspend fun remove(messageId: String, nodeId: String)

    @Query("DELETE FROM reactions WHERE message_id = :messageId")
    suspend fun removeAll(messageId: String)

    /** Drops reaction rows for a chat, for the same reason as attachments. */
    @Query("DELETE FROM reactions WHERE message_id IN (SELECT id FROM messages WHERE chat_id = :chatId)")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE node_id = :nodeId")
    suspend fun find(nodeId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET last_seen_at = :at, transport = :transport WHERE node_id = :nodeId")
    suspend fun touch(nodeId: String, at: Long, transport: String)

    @Query("UPDATE peers SET safety_number = :number WHERE node_id = :nodeId")
    suspend fun setSafetyNumber(nodeId: String, number: String)

    @Query("DELETE FROM peers WHERE last_seen_at < :before")
    suspend fun purgeOlderThan(before: Long): Int
}

/**
 * Groups writes that must land together.
 *
 * A chat delete touches four tables. Left to the repositories the deletes would be
 * separate transactions, and a crash between them would leave a chat with no
 * messages but with rows still pointing at it — exactly the inconsistent state the
 * UI would then render as an empty conversation that cannot be removed.
 */
@Dao
abstract class TransactionDao {

    /**
     * Removes a chat and everything that hangs off it.
     *
     * Attachments and reactions are deleted by hand rather than by cascade: their
     * tables carry no foreign key, so nothing else would ever clean them up.
     */
    @Transaction
    open suspend fun deleteChatCascade(
        chatId: String,
        messageDao: MessageDao,
        attachmentDao: AttachmentDao,
        reactionDao: ReactionDao,
        memberDao: MemberDao,
        chatDao: ChatDao,
    ) {
        attachmentDao.deleteForChat(chatId)
        reactionDao.deleteForChat(chatId)
        messageDao.deleteForChat(chatId)
        memberDao.deleteForChat(chatId)
        chatDao.delete(chatId)
    }
}

/**
 * A message plus its relations.
 *
 * Room fetches the three tables with separate queries and stitches them, which
 * avoids an N+1 storm when a conversation has a few thousand rows.
 */
data class MessageWithRelations(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "message_id")
    val attachments: List<AttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "message_id")
    val reactions: List<ReactionEntity>,
)
