package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messageEvents")
data class MessageEventEntity(
    @PrimaryKey val eventId: Long,
    val command: String = "",
    val messageCode: String = "",
    val sendUserId: String = "",
    val sendDate: Long = 0L,
    val receive: Int = 0
)
