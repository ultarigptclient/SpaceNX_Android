package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.spacenx.messenger.data.local.entity.ChannelEntity

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("SELECT * FROM channels ORDER BY lastChatDate DESC")
    fun getAllFlow(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY lastChatDate DESC")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE channelCode = :channelCode")
    suspend fun getByChannelCode(channelCode: String): ChannelEntity?

    @Query("UPDATE channels SET unreadCount = :count WHERE channelCode = :channelCode")
    suspend fun updateUnreadCount(channelCode: String, count: Int)

    @Query("UPDATE channels SET lastChatDate = :date WHERE channelCode = :channelCode")
    suspend fun updateLastChatDate(channelCode: String, date: Long)

    @Query("DELETE FROM channels WHERE channelCode = :channelCode")
    suspend fun deleteByChannelCode(channelCode: String)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    fun deleteAllSync()

    @Query("UPDATE channels SET lastChatDate = :date, lastChatContents = :contents, masterUserId = :masterUserId WHERE channelCode = :channelCode")
    fun updateLastChatSync(channelCode: String, date: Long, contents: String, masterUserId: String)

    @Query("SELECT * FROM channels")
    fun getAllSync(): List<ChannelEntity>

    @Query("DELETE FROM channels WHERE channelCode = :channelCode")
    fun deleteByChannelCodeSync(channelCode: String)

    @Query("UPDATE channels SET additional = :additional WHERE channelCode = :channelCode")
    suspend fun updateAdditional(channelCode: String, additional: String)
}
