package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threadComments")
data class ThreadCommentEntity(
    @PrimaryKey val commentId: Int,
    val threadCode: String = "",
    val userId: String = "",
    val contents: String = "",
    val createdDate: Long = 0L
)
