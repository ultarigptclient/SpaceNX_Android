package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ProjectSyncMetaEntity

@Dao
interface ProjectSyncMetaDao {

    @Query("SELECT value FROM projectSyncMeta WHERE `key` = :key")
    suspend fun getValue(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProjectSyncMetaEntity)
}
