package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SendChatRequestDTO(
    val channelCode: String,
    val chatCode: String,
    val sendUserId: String,
    val contents: String,
    val additional: JsonObject? = null
)
