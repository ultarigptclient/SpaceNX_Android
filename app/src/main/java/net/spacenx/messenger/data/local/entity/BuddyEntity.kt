package net.spacenx.messenger.data.local.entity

import androidx.room.Entity

@Entity(tableName = "buddies", primaryKeys = ["buddyId", "buddyParent"])
data class BuddyEntity(
    val buddyId: String,
    val buddyParent: String = "",
    val buddyName: String = "",
    val buddyType: String = "",
    val buddyOrder: String = ""
)
