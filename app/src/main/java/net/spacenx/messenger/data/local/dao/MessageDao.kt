package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.spacenx.messenger.data.local.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY sendDate DESC")
    fun getAllFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY sendDate DESC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageCode = :messageCode")
    suspend fun getByMessageCode(messageCode: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageCode IN (:codes)")
    suspend fun getByMessageCodes(codes: List<String>): List<MessageEntity>

    @Query("UPDATE messages SET state = :state WHERE messageCode IN (:codes)")
    suspend fun updateStateForCodes(codes: List<String>, state: Int)

    @Query("DELETE FROM messages WHERE messageCode IN (:codes)")
    suspend fun deleteByMessageCodes(codes: List<String>)

    @Query("UPDATE messages SET state = :state WHERE messageCode = :messageCode")
    suspend fun updateState(messageCode: String, state: Int)

    @Query("DELETE FROM messages WHERE messageCode = :messageCode")
    suspend fun deleteByMessageCode(messageCode: String)

    @Query("SELECT * FROM messages ORDER BY sendDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sendUserId = :userId ORDER BY sendDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getBySender(userId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sendUserId != :userId ORDER BY sendDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getReceived(userId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("UPDATE messages SET state = state | 1 WHERE messageCode = :messageCode")
    suspend fun markRead(messageCode: String)

    @Query("DELETE FROM messages WHERE messageCode = :messageCode")
    suspend fun deleteByCode(messageCode: String)

    @Query("SELECT * FROM messages WHERE sendUserId != :userId AND (state & 2) = 2 ORDER BY sendDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getStarred(userId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sendUserId != :userId")
    suspend fun countReceived(userId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sendUserId != :userId AND (state & 1) = 0")
    suspend fun countReceivedUnread(userId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sendUserId = :userId")
    suspend fun countSent(userId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE (state & 2) = 2")
    suspend fun countStarred(): Int

    @Query("SELECT messageCode FROM messages WHERE messageCode IN (:codes) AND (contents IS NULL OR contents = '')")
    suspend fun getCodesWithoutContents(codes: List<String>): List<String>

    @Query("UPDATE messages SET contents = :contents, rtfContents = :rtfContents, receivers = :receivers WHERE messageCode = :messageCode")
    suspend fun updateContentsAndReceivers(messageCode: String, contents: String, rtfContents: String?, receivers: String)

    @Query("UPDATE messages SET receivers = :receivers WHERE messageCode = :messageCode")
    suspend fun updateReceivers(messageCode: String, receivers: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
