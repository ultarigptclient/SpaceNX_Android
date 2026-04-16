package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.BuddyEntity

@Dao
interface BuddyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(buddy: BuddyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(buddies: List<BuddyEntity>)

    @Query("SELECT * FROM buddies ORDER BY buddyOrder, buddyName ASC")
    suspend fun getAll(): List<BuddyEntity>

    @Query("SELECT * FROM buddies WHERE buddyId = :buddyId")
    suspend fun getByBuddyId(buddyId: String): BuddyEntity?

    @Query("SELECT * FROM buddies WHERE buddyId = :userId OR buddyId LIKE :userId || '@^^@%' LIMIT 1")
    suspend fun getByUserId(userId: String): BuddyEntity?

    @Query("SELECT * FROM buddies WHERE buddyParent = :parentId ORDER BY buddyOrder, buddyName ASC")
    suspend fun getByParent(parentId: String): List<BuddyEntity>

    @Query("DELETE FROM buddies WHERE buddyId = :buddyId")
    suspend fun deleteByBuddyId(buddyId: String)

    @Query("DELETE FROM buddies")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(buddies: List<BuddyEntity>)

    @Query("DELETE FROM buddies")
    fun deleteAllSync()

    @Query("DELETE FROM buddies WHERE buddyParent = :parentId")
    fun deleteByParentSync(parentId: String)

    @Query("DELETE FROM buddies WHERE buddyId = :buddyId AND buddyParent = :buddyParent")
    fun deleteByIdAndParentSync(buddyId: String, buddyParent: String)
}
