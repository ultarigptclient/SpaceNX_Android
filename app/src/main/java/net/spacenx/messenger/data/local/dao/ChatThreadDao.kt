package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ChatThreadEntity
import net.spacenx.messenger.data.local.entity.IssueThreadEntity

@Dao
interface ChatThreadDao {

    // ── ChatThreads ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatThreads(threads: List<ChatThreadEntity>)

    @Query("SELECT * FROM chatThreads WHERE channelCode = :channelCode")
    suspend fun getByChannel(channelCode: String): List<ChatThreadEntity>

    @Query("SELECT * FROM chatThreads WHERE chatCode = :chatCode")
    suspend fun getByChatCode(chatCode: String): ChatThreadEntity?

    @Query("SELECT * FROM chatThreads WHERE threadCode = :threadCode")
    suspend fun getByThreadCode(threadCode: String): ChatThreadEntity?

    @Query("DELETE FROM chatThreads WHERE chatCode = :chatCode")
    suspend fun deleteByChatCode(chatCode: String)

    // ── IssueThreads ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssueThreads(threads: List<IssueThreadEntity>)

    @Query("SELECT * FROM issueThreads WHERE issueCode = :issueCode")
    suspend fun getIssueThreadByIssue(issueCode: String): IssueThreadEntity?

    @Query("SELECT * FROM issueThreads WHERE threadCode = :threadCode")
    suspend fun getIssueThreadByThreadCode(threadCode: String): IssueThreadEntity?

    @Query("SELECT * FROM chatThreads ORDER BY createdDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllChatThreads(limit: Int = 50, offset: Int = 0): List<ChatThreadEntity>

    @Query("SELECT * FROM issueThreads ORDER BY createdDate DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllIssueThreads(limit: Int = 50, offset: Int = 0): List<IssueThreadEntity>
}
