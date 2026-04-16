package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class RefreshTokenRequestDTO(
    val refreshToken: String
)
