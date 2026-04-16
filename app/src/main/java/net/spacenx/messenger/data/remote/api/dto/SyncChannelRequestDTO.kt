package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncChannelRequestDTO(
    val userId: String,
    val channelEventOffset: Long = 0L,
    val reset: Boolean = false
)
