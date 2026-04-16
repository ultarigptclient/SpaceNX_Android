package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.spacenx.messenger.data.local.dao.ProjectDao
import net.spacenx.messenger.data.local.dao.IssueDao
import net.spacenx.messenger.data.local.dao.ChatThreadDao
import net.spacenx.messenger.data.local.dao.ThreadCommentDao
import net.spacenx.messenger.data.local.dao.ProjectSyncMetaDao
import net.spacenx.messenger.data.local.entity.ProjectEntity
import net.spacenx.messenger.data.local.entity.ProjectMemberEntity
import net.spacenx.messenger.data.local.entity.ProjectChannelEntity
import net.spacenx.messenger.data.local.entity.IssueEntity
import net.spacenx.messenger.data.local.entity.ChatThreadEntity
import net.spacenx.messenger.data.local.entity.IssueThreadEntity
import net.spacenx.messenger.data.local.entity.ThreadCommentEntity
import net.spacenx.messenger.data.local.entity.ProjectSyncMetaEntity

@Database(
    entities = [
        ProjectEntity::class,
        ProjectMemberEntity::class,
        ProjectChannelEntity::class,
        IssueEntity::class,
        ChatThreadEntity::class,
        IssueThreadEntity::class,
        ThreadCommentEntity::class,
        ProjectSyncMetaEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class ProjectDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE issues ADD COLUMN issueType TEXT NOT NULL DEFAULT 'TASK'")
            }
        }
    }
    abstract fun projectDao(): ProjectDao
    abstract fun issueDao(): IssueDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun threadCommentDao(): ThreadCommentDao
    abstract fun syncMetaDao(): ProjectSyncMetaDao
}
