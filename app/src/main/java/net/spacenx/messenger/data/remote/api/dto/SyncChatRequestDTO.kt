package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncChatRequestDTO(
    val userId: String,
    val channelCode: String? = null,
    val chatEventOffset: Long = 0L,
    val reset: Boolean = false,
    val limit: Int = 0,
    val beforeEventId: Long = 0L
)
