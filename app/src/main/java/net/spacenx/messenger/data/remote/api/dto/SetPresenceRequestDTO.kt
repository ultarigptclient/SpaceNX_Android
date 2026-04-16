package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SetPresenceRequestDTO(
    val userId: String,
    val presence: Int
)