package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val channelCode: String,
    val channelName: String = "",
    val masterChannelName: String = "",
    val masterUserId: String = "",
    val sendUserId: String = "",
    val channelType: String = "",
    val state: Int = 0,
    val lastChatDate: Long = 0L,
    val lastChatContents: String = "",
    val lastSendUserId: String = "",   // 마지막 채팅 발신자 ID (masterUserId 오용 개선)
    val unreadCount: Int = 0,
    val additional: String = "{}"
)
