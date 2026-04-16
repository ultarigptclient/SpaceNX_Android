package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "syncMeta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: Long = 0L
)
