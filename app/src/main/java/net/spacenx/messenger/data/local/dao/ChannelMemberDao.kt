package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ChannelMemberEntity

@Dao
interface ChannelMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: ChannelMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<ChannelMemberEntity>)

    @Query("SELECT * FROM channelMembers WHERE channelCode = :channelCode")
    suspend fun getMembersByChannel(channelCode: String): List<ChannelMemberEntity>

    @Query("SELECT * FROM channelMembers WHERE channelCode = :channelCode AND (unregistDate IS NULL OR unregistDate = 0)")
    suspend fun getActiveMembersByChannel(channelCode: String): List<ChannelMemberEntity>

    /** 배치 조회: 여러 채널의 active 멤버를 1쿼리로. caller 가 channelCode 별로 group by. */
    @Query("SELECT * FROM channelMembers WHERE channelCode IN (:channelCodes) AND (unregistDate IS NULL OR unregistDate = 0)")
    suspend fun getActiveMembersForChannels(channelCodes: List<String>): List<ChannelMemberEntity>

    @Query("SELECT * FROM channelMembers WHERE channelCode = :channelCode AND userId = :userId")
    suspend fun getMember(channelCode: String, userId: String): ChannelMemberEntity?

    @Query("DELETE FROM channelMembers WHERE channelCode = :channelCode AND userId = :userId")
    suspend fun deleteMember(channelCode: String, userId: String)

    @Query("DELETE FROM channelMembers WHERE channelCode = :channelCode")
    suspend fun deleteByChannel(channelCode: String)

    @Query("DELETE FROM channelMembers")
    suspend fun deleteAll()

    @Query("""
        SELECT cm.channelCode FROM channelMembers cm
        WHERE cm.unregistDate IS NULL OR cm.unregistDate = 0
        GROUP BY cm.channelCode
        HAVING COUNT(*) = 2
        AND SUM(CASE WHEN cm.userId = :user1 THEN 1 ELSE 0 END) = 1
        AND SUM(CASE WHEN cm.userId = :user2 THEN 1 ELSE 0 END) = 1
        LIMIT 1
    """)
    suspend fun findDMChannel(user1: String, user2: String): String?

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(members: List<ChannelMemberEntity>)

    @Query("DELETE FROM channelMembers")
    fun deleteAllSync()

    @Query("DELETE FROM channelMembers WHERE channelCode = :channelCode")
    fun deleteByChannelCodeSync(channelCode: String)

    @Query("UPDATE channelMembers SET unregistDate = :unregistDate WHERE channelCode = :channelCode AND userId = :userId")
    fun unregistMemberSync(channelCode: String, userId: String, unregistDate: Long)

    @Query("DELETE FROM channelMembers WHERE channelCode = :channelCode AND userId = :userId")
    fun deleteMemberSync(channelCode: String, userId: String)
}
