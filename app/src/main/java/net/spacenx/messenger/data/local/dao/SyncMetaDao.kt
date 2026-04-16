package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.SyncMetaEntity

@Dao
interface SyncMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(meta: SyncMetaEntity)

    @Query("SELECT value FROM syncMeta WHERE `key` = :key")
    suspend fun getValue(key: String): Long?

    @Query("DELETE FROM syncMeta WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM syncMeta")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(meta: SyncMetaEntity)

    @Query("SELECT value FROM syncMeta WHERE `key` = :key")
    fun getValueSync(key: String): Long?
}
