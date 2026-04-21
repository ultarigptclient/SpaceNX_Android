package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.AttachDao
import net.spacenx.messenger.data.local.dao.MessageDao
import net.spacenx.messenger.data.local.dao.MessageEventDao
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import net.spacenx.messenger.data.local.entity.AttachEntity
import net.spacenx.messenger.data.local.entity.MessageEntity
import net.spacenx.messenger.data.local.entity.MessageEventEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity

@Database(
    entities = [
        MessageEntity::class,
        AttachEntity::class,
        MessageEventEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachDao(): AttachDao
    abstract fun messageEventDao(): MessageEventDao
    abstract fun syncMetaDao(): SyncMetaDao
}
