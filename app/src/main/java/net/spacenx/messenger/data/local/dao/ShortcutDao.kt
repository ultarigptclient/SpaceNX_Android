package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ShortcutEntity

@Dao
interface ShortcutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shortcuts: List<ShortcutEntity>)

    @Query("SELECT * FROM shortcuts WHERE userId = :userId ORDER BY orderIndex ASC, createdDate ASC")
    suspend fun getByUser(userId: String): List<ShortcutEntity>

    @Query("SELECT * FROM shortcuts WHERE shortcutId = :shortcutId")
    suspend fun getById(shortcutId: Long): ShortcutEntity?

    @Query("DELETE FROM shortcuts WHERE shortcutId = :shortcutId")
    suspend fun delete(shortcutId: Long)

    @Query("UPDATE shortcuts SET displayName = :displayName WHERE shortcutId = :shortcutId")
    suspend fun rename(shortcutId: Long, displayName: String)

    @Query("UPDATE shortcuts SET orderIndex = :orderIndex WHERE shortcutId = :shortcutId")
    suspend fun updateOrder(shortcutId: Long, orderIndex: Int)

    @Query("DELETE FROM shortcuts WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)
}
