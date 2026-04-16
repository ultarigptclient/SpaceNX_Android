package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chats",
    primaryKeys = ["channelCode", "chatCode"],
    indices = [
        Index("channelCode"),
        Index(value = ["channelCode", "sendDate"])
    ]
)
data class ChatEntity(
    val channelCode: String,
    val chatCode: String,
    val sendUserId: String? = null,
    val contents: String = "",
    val sendDate: Long = 0L,
    val additional: String? = null,
    val chatType: Int = 0,
    val systemCommand: String? = null,
    val chatFont: String = "{}",
    val state: Int = 0
)
