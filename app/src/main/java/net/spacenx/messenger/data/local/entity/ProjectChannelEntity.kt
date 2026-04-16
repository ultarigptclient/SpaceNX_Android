package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(tableName = "projectChannels", primaryKeys = ["projectCode", "channelCode"])
data class ProjectChannelEntity(
    val projectCode: String,
    val channelCode: String,
    val createdDate: Long = 0L
)
