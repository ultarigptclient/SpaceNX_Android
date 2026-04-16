package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.SettingDao
import net.spacenx.messenger.data.local.entity.SettingEntity

@Database(
    entities = [SettingEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SettingDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
}
