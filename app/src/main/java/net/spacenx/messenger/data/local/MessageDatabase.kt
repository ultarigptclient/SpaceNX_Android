package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = true
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachDao(): AttachDao
    abstract fun messageEventDao(): MessageEventDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        /**
         * v1 → v2: messages.sendDate 단일 인덱스 + (state, sendDate) 복합 인덱스 추가.
         * 기존 `ORDER BY sendDate DESC` 쿼리 full-scan 방지.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sendDate ON messages(sendDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_state_sendDate ON messages(state, sendDate)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachs ADD COLUMN fileId TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
