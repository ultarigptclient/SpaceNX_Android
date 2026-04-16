package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index("deptId")]
)
data class UserEntity(
    @PrimaryKey val userId: String,
    val deptId: String = "",
    val loginId: String = "",
    val userInfo: String = "{}",
    val userOrder: String = ""
)
