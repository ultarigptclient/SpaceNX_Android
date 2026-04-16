package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 4,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun channelMemberDao(): ChannelMemberDao
    abstract fun channelOffsetDao(): ChannelOffsetDao
    abstract fun chatDao(): ChatDao
    abstract fun chatEventDao(): ChatEventDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        /** v1→v2: chats 테이블에 복합 인덱스 추가 (데이터 손실 없음) */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chats_channelCode_sendDate ON chats(channelCode, sendDate)"
                )
            }
        }

        /** v2→v3: channels 테이블에 state 컬럼 추가 (ChatRoomType 비트마스크, 기본값 0) */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE channels ADD COLUMN state INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3→v4: channels 테이블에 lastSendUserId 추가 (masterUserId 오용 → 명확한 컬럼 분리) */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE channels ADD COLUMN lastSendUserId TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
