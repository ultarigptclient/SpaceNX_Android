package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cal_events")
data class CalEventEntity(
    @PrimaryKey val calCode: String,
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val calType: String = "PERSONAL",
    val startDate: Long = 0L,
    val endDate: Long = 0L,
    val allDay: Int = 0,       // 0=false, 1=true
    val color: String = "",
    val location: String = "",
    val modDate: Long = 0L,
    val createdDate: Long = 0L
)
