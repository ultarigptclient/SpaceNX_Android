package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "channelMembers",
    primaryKeys = ["channelCode", "userId"],
    indices = [Index("channelCode")]
)
data class ChannelMemberEntity(
    val channelCode: String,
    val userId: String,
    val registDate: Long = 0L,
    val unregistDate: Long? = null
)
