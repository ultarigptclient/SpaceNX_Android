package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.CommonDao
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import net.spacenx.messenger.data.local.entity.CommonEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity

@Database(
    entities = [
        CommonEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class CommonDatabase : RoomDatabase() {
    abstract fun commonDao(): CommonDao
    abstract fun syncMetaDao(): SyncMetaDao
}
