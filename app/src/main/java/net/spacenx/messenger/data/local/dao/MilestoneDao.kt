package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.MilestoneEntity

@Dao
interface MilestoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(milestones: List<MilestoneEntity>)

    @Query("SELECT * FROM milestones WHERE projectCode = :projectCode ORDER BY targetDate ASC, createdDate ASC")
    suspend fun getByProject(projectCode: String): List<MilestoneEntity>

    @Query("SELECT * FROM milestones WHERE milestoneCode = :milestoneCode")
    suspend fun getByCode(milestoneCode: String): MilestoneEntity?

    @Query("DELETE FROM milestones WHERE milestoneCode = :milestoneCode")
    suspend fun delete(milestoneCode: String)

    @Query("DELETE FROM milestones WHERE projectCode = :projectCode")
    suspend fun deleteByProject(projectCode: String)
}
