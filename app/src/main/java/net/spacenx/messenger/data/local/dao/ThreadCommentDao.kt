package net.spacenx.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.spacenx.messenger.data.local.entity.ThreadCommentEntity

@Dao
interface ThreadCommentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(comments: List<ThreadCommentEntity>)

    @Query("SELECT * FROM threadComments WHERE threadCode = :threadCode ORDER BY createdDate ASC")
    suspend fun getByThreadCode(threadCode: String): List<ThreadCommentEntity>

    @Query("SELECT * FROM threadComments WHERE commentId = :commentId")
    suspend fun getById(commentId: Int): ThreadCommentEntity?

    @Query("SELECT COUNT(*) FROM threadComments WHERE threadCode = :threadCode")
    suspend fun countByThreadCode(threadCode: String): Int

    @Query("DELETE FROM threadComments WHERE commentId = :commentId")
    suspend fun delete(commentId: Int)

    @Query("DELETE FROM threadComments WHERE threadCode = :threadCode")
    suspend fun deleteByThreadCode(threadCode: String)
}
