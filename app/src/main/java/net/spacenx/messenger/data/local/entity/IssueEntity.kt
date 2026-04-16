package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "issues")
data class IssueEntity(
    @PrimaryKey val issueCode: String,
    val projectCode: String = "",
    val channelCode: String = "",
    val title: String = "",
    val description: String = "",
    val issueType: String = "TASK",
    val issueStatus: String = "TODO",
    val priority: String = "NORMAL",
    val assigneeUserId: String = "",
    val reporterUserId: String = "",
    val labels: String = "",
    val dueDate: Long = 0L,
    val completedDate: Long = 0L,
    val modDate: Long = 0L,
    val createdDate: Long = 0L,
    val threadCode: String = "",
    val commentCount: Int = 0
)
