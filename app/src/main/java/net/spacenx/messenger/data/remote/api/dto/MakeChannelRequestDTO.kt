package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MakeChannelRequestDTO(
    val channelCode: String,
    val channelName: String,
    val sendUserId: String,
    val users: List<String>
)
