package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.AttachEntity

@Dao
interface AttachDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attach: AttachEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachs: List<AttachEntity>)

    @Query("SELECT * FROM attachs WHERE messageCode = :messageCode")
    suspend fun getByMessageCode(messageCode: String): List<AttachEntity>

    @Query("DELETE FROM attachs WHERE messageCode = :messageCode")
    suspend fun deleteByMessageCode(messageCode: String)

    @Query("DELETE FROM attachs WHERE messageCode IN (:codes)")
    suspend fun deleteByMessageCodes(codes: List<String>)

    @Query("DELETE FROM attachs")
    suspend fun deleteAll()
}
