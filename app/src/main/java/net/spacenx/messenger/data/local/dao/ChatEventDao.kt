package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ChatEventEntity

@Dao
interface ChatEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ChatEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ChatEventEntity>)

    @Query("SELECT * FROM chatEvents WHERE channelCode = :channelCode ORDER BY eventId DESC")
    suspend fun getByChannel(channelCode: String): List<ChatEventEntity>

    @Query("SELECT * FROM chatEvents WHERE eventId = :eventId")
    suspend fun getByEventId(eventId: Long): ChatEventEntity?

    @Query("SELECT MAX(eventId) FROM chatEvents")
    suspend fun getMaxEventId(): Long?

    @Query("DELETE FROM chatEvents WHERE channelCode = :channelCode")
    suspend fun deleteByChannel(channelCode: String)

    @Query("DELETE FROM chatEvents")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(events: List<ChatEventEntity>)

    @Query("DELETE FROM chatEvents")
    fun deleteAllSync()
}
