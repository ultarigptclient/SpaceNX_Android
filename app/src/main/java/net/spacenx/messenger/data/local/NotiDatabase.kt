package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = true
)
abstract class NotiDatabase : RoomDatabase() {
    abstract fun notiDao(): NotiDao
    abstract fun notiEventDao(): NotiEventDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        /**
         * v1 → v2: notis.sendDate 인덱스 추가. NotiDao 의 `ORDER BY sendDate DESC` 쿼리 full-scan 방지.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notis_sendDate ON notis(sendDate)")
            }
        }
    }
}
