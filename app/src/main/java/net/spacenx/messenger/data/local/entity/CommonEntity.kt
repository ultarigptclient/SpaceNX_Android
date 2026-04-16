package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config")
data class CommonEntity(
    @PrimaryKey val key: String,
    val value: String = ""
)