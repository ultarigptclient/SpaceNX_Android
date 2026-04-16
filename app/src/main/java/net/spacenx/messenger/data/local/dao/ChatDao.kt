package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.spacenx.messenger.data.local.entity.ChatEntity

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chats: List<ChatEntity>)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE channelCode = :channelCode ORDER BY sendDate DESC")
    fun getByChannelFlow(channelCode: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE channelCode = :channelCode ORDER BY sendDate DESC")
    suspend fun getByChannel(channelCode: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE chatCode = :chatCode")
    suspend fun getByChatCode(chatCode: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE channelCode = :channelCode ORDER BY sendDate DESC LIMIT :limit")
    suspend fun getRecent(channelCode: String, limit: Int): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE channelCode = :channelCode AND sendDate < :beforeDate ORDER BY sendDate DESC LIMIT :limit")
    suspend fun getBeforeDate(channelCode: String, beforeDate: Long, limit: Int): List<ChatEntity>

    @Query("DELETE FROM chats WHERE chatCode = :chatCode")
    suspend fun deleteByChatCode(chatCode: String)

    @Query("DELETE FROM chats WHERE channelCode = :channelCode")
    suspend fun deleteByChannel(channelCode: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(chats: List<ChatEntity>)

    @Query("DELETE FROM chats")
    fun deleteAllSync()

    @Query("SELECT * FROM chats WHERE channelCode = :channelCode ORDER BY sendDate DESC LIMIT 1")
    fun getLastChatSync(channelCode: String): ChatEntity?

    @Query("DELETE FROM chats WHERE channelCode = :channelCode AND chatCode = :chatCode")
    fun deleteByChatCodeSync(channelCode: String, chatCode: String)

    @Query("UPDATE chats SET state = 1 WHERE channelCode = :channelCode AND chatCode = :chatCode")
    fun markDeletedByChatCodeSync(channelCode: String, chatCode: String)

    /** REACTION/MOD 이벤트: additional 컬럼만 갱신 (기존 chat 행이 있을 때만) */
    @Query("UPDATE chats SET additional = :additional WHERE chatCode = :chatCode")
    fun updateAdditionalByChatCodeSync(chatCode: String, additional: String)

    @Query("SELECT * FROM chats WHERE chatCode = :chatCode")
    fun getByChatCodeSync(chatCode: String): ChatEntity?

    /** 미확인 메시지 수: offsetDate 이후 + 내가 보내지 않은 메시지 */
    @Query("SELECT COUNT(*) FROM chats WHERE channelCode = :channelCode AND sendDate > :offsetDate AND sendUserId != :myUserId AND state = 0")
    suspend fun countUnread(channelCode: String, offsetDate: Long, myUserId: String): Int
}
