package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(tableName = "chatThreads", primaryKeys = ["chatCode", "threadCode"])
data class ChatThreadEntity(
    val chatCode: String,
    val threadCode: String,
    val channelCode: String = "",
    val commentCount: Int = 0,
    val createdDate: Long = 0L
)
