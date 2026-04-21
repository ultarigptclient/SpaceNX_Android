package net.spacenx.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun issueDao(): IssueDao
    abstract fun calEventDao(): CalEventDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun threadCommentDao(): ThreadCommentDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun syncMetaDao(): ProjectSyncMetaDao
}
