package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(tableName = "projectMembers", primaryKeys = ["projectCode", "userId"])
data class ProjectMemberEntity(
    val projectCode: String,
    val userId: String,
    val createdDate: Long = 0L
)
