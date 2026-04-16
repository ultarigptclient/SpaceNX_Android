package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "channelOffsets",
    primaryKeys = ["channelCode", "userId"]
)
data class ChannelOffsetEntity(
    val channelCode: String,
    val userId: String,
    val offsetDate: Long = 0L
)
