package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.NotiDao
import net.spacenx.messenger.data.local.dao.NotiEventDao
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import net.spacenx.messenger.data.local.entity.NotiEntity
import net.spacenx.messenger.data.local.entity.NotiEventEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity

@Database(
    entities = [
        NotiEntity::class,
        NotiEventEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NotiDatabase : RoomDatabase() {
    abstract fun notiDao(): NotiDao
    abstract fun notiEventDao(): NotiEventDao
    abstract fun syncMetaDao(): SyncMetaDao
}
