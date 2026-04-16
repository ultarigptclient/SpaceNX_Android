package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chatEvents",
    indices = [Index("channelCode")]
)
data class ChatEventEntity(
    @PrimaryKey val eventId: Long,
    val command: String = "",
    val channelCode: String = "",
    val chatCode: String = "",
    val sendUserId: String = "",
    val sendDate: Long = 0L
)
