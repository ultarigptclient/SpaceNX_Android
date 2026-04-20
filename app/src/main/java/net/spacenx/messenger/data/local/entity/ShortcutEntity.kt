package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shortcuts", indices = [Index("userId")])
data class ShortcutEntity(
    @PrimaryKey val shortcutId: Long,
    val userId: String = "",
    val shortcutType: String = "",
    val targetId: String = "",
    val displayName: String = "",
    val icon: String = "",
    val orderIndex: Int = 0,
    val modDate: Long = 0L,
    val createdDate: Long = 0L
)
