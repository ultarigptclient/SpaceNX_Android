package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notiEvents")
data class NotiEventEntity(
    @PrimaryKey val eventId: Long,
    val command: String = "",
    val notiCode: String = "",
    val sendDate: Long = 0L
)
