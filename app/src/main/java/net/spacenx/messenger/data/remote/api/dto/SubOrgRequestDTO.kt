package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SubOrgRequestDTO(
    val userId: String,
    val deptId: String
)
