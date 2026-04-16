package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val projectCode: String,
    val projectName: String = "",
    val icon: String = "",
    val color: String = "",
    val projectStatus: String = "ACTIVE",
    val ownerUserId: String = "",
    val description: String = "",
    val deadline: Long = 0L,
    val modDate: Long = 0L,
    val createdDate: Long = 0L
)
