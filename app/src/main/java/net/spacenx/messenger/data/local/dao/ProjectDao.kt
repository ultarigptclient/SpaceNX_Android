package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ProjectEntity
import net.spacenx.messenger.data.local.entity.ProjectMemberEntity
import net.spacenx.messenger.data.local.entity.ProjectChannelEntity

@Dao
interface ProjectDao {

    // ── Projects ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjects(projects: List<ProjectEntity>)

    @Query("SELECT * FROM projects")
    suspend fun getAllProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE projectCode IN (SELECT projectCode FROM projectMembers WHERE userId = :userId)")
    suspend fun getProjectsByUser(userId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE projectCode = :projectCode")
    suspend fun getProject(projectCode: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE projectCode = :projectCode")
    suspend fun deleteProject(projectCode: String)

    // ── ProjectMembers ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ProjectMemberEntity>)

    @Query("SELECT * FROM projectMembers WHERE projectCode = :projectCode")
    suspend fun getMembers(projectCode: String): List<ProjectMemberEntity>

    @Query("DELETE FROM projectMembers WHERE projectCode = :projectCode AND userId = :userId")
    suspend fun deleteMember(projectCode: String, userId: String)

    @Query("DELETE FROM projectMembers WHERE projectCode = :projectCode")
    suspend fun deleteAllMembers(projectCode: String)

    // ── ProjectChannels ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ProjectChannelEntity>)

    @Query("SELECT * FROM projectChannels WHERE projectCode = :projectCode")
    suspend fun getChannels(projectCode: String): List<ProjectChannelEntity>

    @Query("DELETE FROM projectChannels WHERE projectCode = :projectCode")
    suspend fun deleteAllChannels(projectCode: String)
}
