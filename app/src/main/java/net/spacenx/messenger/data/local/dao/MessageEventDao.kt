package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.MessageEventEntity

@Dao
interface MessageEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MessageEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MessageEventEntity>)

    @Query("SELECT * FROM messageEvents WHERE messageCode = :messageCode ORDER BY eventId DESC")
    suspend fun getByMessage(messageCode: String): List<MessageEventEntity>

    @Query("SELECT MAX(eventId) FROM messageEvents")
    suspend fun getMaxEventId(): Long?

    @Query("DELETE FROM messageEvents WHERE messageCode = :messageCode")
    suspend fun deleteByMessage(messageCode: String)

    @Query("DELETE FROM messageEvents WHERE messageCode IN (:codes)")
    suspend fun deleteByMessages(codes: List<String>)

    @Query("DELETE FROM messageEvents")
    suspend fun deleteAll()
}
