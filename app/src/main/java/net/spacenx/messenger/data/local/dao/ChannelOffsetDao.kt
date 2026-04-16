package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ChannelOffsetEntity

@Dao
interface ChannelOffsetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(offset: ChannelOffsetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(offsets: List<ChannelOffsetEntity>)

    @Query("SELECT * FROM channelOffsets WHERE channelCode = :channelCode")
    suspend fun getByChannel(channelCode: String): List<ChannelOffsetEntity>

    @Query("SELECT * FROM channelOffsets WHERE channelCode = :channelCode AND userId = :userId")
    suspend fun getOffset(channelCode: String, userId: String): ChannelOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(offset: ChannelOffsetEntity)

    @Query("SELECT * FROM channelOffsets WHERE channelCode = :channelCode AND userId = :userId")
    suspend fun get(channelCode: String, userId: String): ChannelOffsetEntity?

    @Query("DELETE FROM channelOffsets WHERE channelCode = :channelCode")
    suspend fun deleteByChannel(channelCode: String)

    @Query("DELETE FROM channelOffsets")
    suspend fun deleteAll()

    // 동기 버전 (runInTransaction 내부용)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllSync(offsets: List<ChannelOffsetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSync(offset: ChannelOffsetEntity)
}
