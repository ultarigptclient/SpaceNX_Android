package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.ChannelDao
import net.spacenx.messenger.data.local.dao.ChannelMemberDao
import net.spacenx.messenger.data.local.dao.ChannelOffsetDao
import net.spacenx.messenger.data.local.dao.ChatDao
import net.spacenx.messenger.data.local.dao.ChatEventDao
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import net.spacenx.messenger.data.local.entity.ChannelEntity
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity
import net.spacenx.messenger.data.local.entity.ChannelOffsetEntity
import net.spacenx.messenger.data.local.entity.ChatEntity
import net.spacenx.messenger.data.local.entity.ChatEventEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity

@Database(
    entities = [
        ChannelEntity::class,
        ChannelMemberEntity::class,
        ChannelOffsetEntity::class,
        ChatEntity::class,
        ChatEventEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun channelMemberDao(): ChannelMemberDao
    abstract fun channelOffsetDao(): ChannelOffsetDao
    abstract fun chatDao(): ChatDao
    abstract fun chatEventDao(): ChatEventDao
    abstract fun syncMetaDao(): SyncMetaDao
}
