package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.CalEventEntity

@Dao
interface CalEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalEventEntity>)

    @Query("SELECT * FROM cal_events WHERE userId = :userId")
    suspend fun getByUser(userId: String): List<CalEventEntity>

    @Query("""
        SELECT * FROM cal_events
        WHERE userId = :userId
          AND startDate < :toMs
          AND endDate >= :fromMs
    """)
    suspend fun getByUserAndMonth(userId: String, fromMs: Long, toMs: Long): List<CalEventEntity>

    @Query("DELETE FROM cal_events WHERE calCode = :calCode")
    suspend fun delete(calCode: String)

    @Query("DELETE FROM cal_events WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)
}
