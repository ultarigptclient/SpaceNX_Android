package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.spacenx.messenger.data.local.dao.ProjectDao
import net.spacenx.messenger.data.local.dao.IssueDao
import net.spacenx.messenger.data.local.dao.CalEventDao
import net.spacenx.messenger.data.local.dao.ChatThreadDao
import net.spacenx.messenger.data.local.dao.ThreadCommentDao
import net.spacenx.messenger.data.local.dao.MilestoneDao
import net.spacenx.messenger.data.local.dao.ShortcutDao
import net.spacenx.messenger.data.local.dao.ProjectSyncMetaDao
import net.spacenx.messenger.data.local.entity.ProjectEntity
import net.spacenx.messenger.data.local.entity.ProjectMemberEntity
import net.spacenx.messenger.data.local.entity.ProjectChannelEntity
import net.spacenx.messenger.data.local.entity.IssueEntity
import net.spacenx.messenger.data.local.entity.CalEventEntity
import net.spacenx.messenger.data.local.entity.ChatThreadEntity
import net.spacenx.messenger.data.local.entity.IssueThreadEntity
import net.spacenx.messenger.data.local.entity.ThreadCommentEntity
import net.spacenx.messenger.data.local.entity.MilestoneEntity
import net.spacenx.messenger.data.local.entity.ShortcutEntity
import net.spacenx.messenger.data.local.entity.ProjectSyncMetaEntity

@Database(
    entities = [
        ProjectEntity::class,
        ProjectMemberEntity::class,
        ProjectChannelEntity::class,
        IssueEntity::class,
        CalEventEntity::class,
        ChatThreadEntity::class,
        IssueThreadEntity::class,
        ThreadCommentEntity::class,
        MilestoneEntity::class,
        ShortcutEntity::class,
        ProjectSyncMetaEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class ProjectDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE issues ADD COLUMN issueType TEXT NOT NULL DEFAULT 'TASK'")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chatThreads ADD COLUMN chatContents TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cal_events (
                        calCode TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL DEFAULT '',
                        description TEXT NOT NULL DEFAULT '',
                        calType TEXT NOT NULL DEFAULT 'PERSONAL',
                        startDate INTEGER NOT NULL DEFAULT 0,
                        endDate INTEGER NOT NULL DEFAULT 0,
                        allDay INTEGER NOT NULL DEFAULT 0,
                        color TEXT NOT NULL DEFAULT '',
                        location TEXT NOT NULL DEFAULT '',
                        modDate INTEGER NOT NULL DEFAULT 0,
                        createdDate INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // issues 테이블 컬럼 2개 추가 (parentIssueCode, milestoneCode)
                db.execSQL("ALTER TABLE issues ADD COLUMN parentIssueCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE issues ADD COLUMN milestoneCode TEXT NOT NULL DEFAULT ''")
                // milestones 테이블 생성
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS milestones (
                        milestoneCode TEXT NOT NULL PRIMARY KEY,
                        projectCode TEXT NOT NULL DEFAULT '',
                        milestoneName TEXT NOT NULL DEFAULT '',
                        description TEXT NOT NULL DEFAULT '',
                        milestoneStatus TEXT NOT NULL DEFAULT 'TODO',
                        ownerUserId TEXT NOT NULL DEFAULT '',
                        startDate INTEGER NOT NULL DEFAULT 0,
                        targetDate INTEGER NOT NULL DEFAULT 0,
                        modDate INTEGER NOT NULL DEFAULT 0,
                        createdDate INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_milestones_projectCode ON milestones(projectCode)")
                // shortcuts 테이블 생성
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS shortcuts (
                        shortcutId INTEGER NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL DEFAULT '',
                        shortcutType TEXT NOT NULL DEFAULT '',
                        targetId TEXT NOT NULL DEFAULT '',
                        displayName TEXT NOT NULL DEFAULT '',
                        icon TEXT NOT NULL DEFAULT '',
                        orderIndex INTEGER NOT NULL DEFAULT 0,
                        modDate INTEGER NOT NULL DEFAULT 0,
                        createdDate INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shortcuts_userId ON shortcuts(userId)")
            }
        }
    }
    abstract fun projectDao(): ProjectDao
    abstract fun issueDao(): IssueDao
    abstract fun calEventDao(): CalEventDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun threadCommentDao(): ThreadCommentDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun syncMetaDao(): ProjectSyncMetaDao
}
