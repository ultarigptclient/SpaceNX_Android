package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(tableName = "issueThreads", primaryKeys = ["issueCode", "threadCode"])
data class IssueThreadEntity(
    val issueCode: String,
    val threadCode: String,
    val createdDate: Long = 0L
)
