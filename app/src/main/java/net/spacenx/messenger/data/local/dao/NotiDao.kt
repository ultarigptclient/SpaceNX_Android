package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.spacenx.messenger.data.local.entity.NotiEntity

@Dao
interface NotiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(noti: NotiEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notis: List<NotiEntity>)

    @Update
    suspend fun update(noti: NotiEntity)

    @Query("SELECT * FROM notis ORDER BY sendDate DESC")
    fun getAllFlow(): Flow<List<NotiEntity>>

    @Query("SELECT * FROM notis ORDER BY sendDate DESC")
    suspend fun getAll(): List<NotiEntity>

    @Query("SELECT * FROM notis WHERE notiCode = :notiCode")
    suspend fun getByNotiCode(notiCode: String): NotiEntity?

    @Query("UPDATE notis SET read = 1 WHERE notiCode = :notiCode")
    suspend fun markAsRead(notiCode: String)

    @Query("SELECT * FROM notis WHERE read = 0 ORDER BY sendDate DESC")
    suspend fun getUnread(): List<NotiEntity>

    @Query("DELETE FROM notis WHERE notiCode = :notiCode")
    suspend fun deleteByNotiCode(notiCode: String)

    @Query("UPDATE notis SET read = 1 WHERE notiCode = :notiCode")
    suspend fun markRead(notiCode: String)

    @Query("DELETE FROM notis WHERE notiCode = :notiCode")
    suspend fun deleteByCode(notiCode: String)

    @Query("SELECT * FROM notis ORDER BY sendDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Int): List<NotiEntity>

    @Query("SELECT COUNT(*) FROM notis")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM notis WHERE read = 0")
    suspend fun countUnread(): Int

    @Query("DELETE FROM notis")
    suspend fun deleteAll()
}
