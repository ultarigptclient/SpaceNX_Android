package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.spacenx.messenger.data.local.dao.BuddyDao
import net.spacenx.messenger.data.local.dao.DeptDao
import net.spacenx.messenger.data.local.dao.SyncMetaDao
import net.spacenx.messenger.data.local.dao.UserDao
import net.spacenx.messenger.data.local.entity.BuddyEntity
import net.spacenx.messenger.data.local.entity.DeptEntity
import net.spacenx.messenger.data.local.entity.SyncMetaEntity
import net.spacenx.messenger.data.local.entity.UserEntity

@Database(
    entities = [
        DeptEntity::class,
        UserEntity::class,
        BuddyEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OrgDatabase : RoomDatabase() {
    abstract fun deptDao(): DeptDao
    abstract fun userDao(): UserDao
    abstract fun buddyDao(): BuddyDao
    abstract fun syncMetaDao(): SyncMetaDao
}