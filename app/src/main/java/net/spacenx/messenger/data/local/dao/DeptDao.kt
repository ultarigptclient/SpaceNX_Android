package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.DeptEntity

@Dao
interface DeptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dept: DeptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(depts: List<DeptEntity>)

    @Query("SELECT * FROM depts")
    suspend fun getAll(): List<DeptEntity>

    @Query("SELECT * FROM depts WHERE deptId = :deptId")
    suspend fun getByDeptId(deptId: String): DeptEntity?

    @Query("SELECT * FROM depts WHERE parentDept = :parentDept ORDER BY deptOrder")
    suspend fun getByParent(parentDept: String): List<DeptEntity>

    @Query("SELECT * FROM depts WHERE parentDept = '' OR parentDept = deptId OR parentDept NOT IN (SELECT deptId FROM depts) ORDER BY deptOrder")
    suspend fun getRootDepts(): List<DeptEntity>

    @Query("DELETE FROM depts WHERE deptId IN (:deptIds)")
    suspend fun deleteByIds(deptIds: List<String>)

    @Query("DELETE FROM depts")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(depts: List<DeptEntity>)

    @Query("DELETE FROM depts WHERE deptId IN (:deptIds)")
    fun deleteByIdsSync(deptIds: List<String>)

    @Query("DELETE FROM depts")
    fun deleteAllSync()
}
