package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.IssueEntity

@Dao
interface IssueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(issues: List<IssueEntity>)

    @Query("SELECT * FROM issues WHERE projectCode = :projectCode")
    suspend fun getByProject(projectCode: String): List<IssueEntity>

    @Query("SELECT * FROM issues WHERE assigneeUserId = :userId")
    suspend fun getByAssignee(userId: String): List<IssueEntity>

    @Query("SELECT * FROM issues")
    suspend fun getAll(): List<IssueEntity>

    @Query("SELECT * FROM issues WHERE issueCode = :issueCode")
    suspend fun getByCode(issueCode: String): IssueEntity?

    @Query("DELETE FROM issues WHERE issueCode = :issueCode")
    suspend fun delete(issueCode: String)

    @Query("DELETE FROM issues WHERE projectCode = :projectCode")
    suspend fun deleteByProject(projectCode: String)

    @Query("SELECT * FROM issues WHERE (projectCode = '' OR projectCode IS NULL) AND assigneeUserId = :userId")
    suspend fun getTodosByUser(userId: String): List<IssueEntity>

    @Query("SELECT * FROM issues WHERE projectCode = '' OR projectCode IS NULL")
    suspend fun getAllTodos(): List<IssueEntity>
}
