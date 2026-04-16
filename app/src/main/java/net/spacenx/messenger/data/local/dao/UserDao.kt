package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.UserEntity

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users")
    suspend fun getAll(): List<UserEntity>

    @Query("SELECT * FROM users WHERE userId = :userId")
    suspend fun getByUserId(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE userId IN (:userIds)")
    suspend fun getByUserIds(userIds: List<String>): List<UserEntity>

    @Query("SELECT * FROM users WHERE deptId = :deptId ORDER BY userOrder, loginId ASC")
    suspend fun getByDeptId(deptId: String): List<UserEntity>

    @Query("SELECT * FROM users WHERE userInfo LIKE '%' || :keyword || '%'")
    suspend fun searchByUserInfo(keyword: String): List<UserEntity>

    @Query("DELETE FROM users WHERE userId IN (:userIds)")
    suspend fun deleteByIds(userIds: List<String>)

    @Query("DELETE FROM users")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(users: List<UserEntity>)

    @Query("DELETE FROM users WHERE userId IN (:userIds)")
    fun deleteByIdsSync(userIds: List<String>)

    @Query("DELETE FROM users")
    fun deleteAllSync()
}
