package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchUserRequestDTO(
    val type: String,
    val keyword: String
)
