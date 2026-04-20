package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "milestones", indices = [Index("projectCode")])
data class MilestoneEntity(
    @PrimaryKey val milestoneCode: String,
    val projectCode: String = "",
    val milestoneName: String = "",
    val description: String = "",
    val milestoneStatus: String = "TODO",
    val ownerUserId: String = "",
    val startDate: Long = 0L,
    val targetDate: Long = 0L,
    val modDate: Long = 0L,
    val createdDate: Long = 0L
)
