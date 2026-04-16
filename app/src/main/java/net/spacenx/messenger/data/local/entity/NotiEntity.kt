package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// NotiDao 의 `ORDER BY sendDate DESC` 쿼리(getAll, loadMore, unread) 가 full-scan 되던 것 방지.
@Entity(
    tableName = "notis",
    indices = [Index(value = ["sendDate"])]
)
data class NotiEntity(
    @PrimaryKey val notiCode: String,
    val systemCode: String = "",
    val systemName: String = "",
    val senderName: String = "",
    val notiTitle: String = "",
    val notiContents: String = "",
    val linkUrl: String? = null,
    val alertType: String = "",
    val sendDate: Long = 0L,
    val read: Int = 0
)
