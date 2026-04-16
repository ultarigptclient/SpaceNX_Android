package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// sendDate 인덱스: MessageDao 의 모든 쿼리(`ORDER BY sendDate DESC`) 가 full-scan 되던 것 방지.
// 복합 인덱스(state, sendDate): 읽음/안읽음 필터 + sendDate 정렬 고속화.
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["sendDate"]),
        Index(value = ["state", "sendDate"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageCode: String,
    val sendUserId: String = "",
    val title: String = "",
    val contents: String = "",
    val rtfContents: String? = null,
    val sendDate: Long = 0L,
    val scheduleDate: Long = 0L,
    val state: Int = 0,
    val receivers: String = "[]",
    val messageType: Int = 0,
    val important: Boolean = false,
    val individual: Boolean = false,
    val silent: Boolean = false,
    val retrieved: Boolean = false,
    val attachInfo: String? = null
)
