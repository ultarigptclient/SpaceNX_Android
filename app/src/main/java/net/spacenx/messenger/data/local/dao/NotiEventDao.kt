package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.NotiEventEntity

@Dao
interface NotiEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: NotiEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<NotiEventEntity>)

    @Query("SELECT * FROM notiEvents WHERE notiCode = :notiCode ORDER BY eventId DESC")
    suspend fun getByNoti(notiCode: String): List<NotiEventEntity>

    @Query("SELECT MAX(eventId) FROM notiEvents")
    suspend fun getMaxEventId(): Long?

    @Query("DELETE FROM notiEvents WHERE notiCode = :notiCode")
    suspend fun deleteByNoti(notiCode: String)

    @Query("DELETE FROM notiEvents")
    suspend fun deleteAll()
}
