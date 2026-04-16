package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.SettingEntity

@Dao
interface SettingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SettingEntity)

    @Query("SELECT * FROM setting WHERE `key` = :key")
    suspend fun getByKey(key: String): SettingEntity?

    @Query("SELECT value FROM setting WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT * FROM setting")
    suspend fun getAll(): List<SettingEntity>

    @Query("DELETE FROM setting")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(entity: SettingEntity)
}
