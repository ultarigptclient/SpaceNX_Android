package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncConfigRequestDTO(
    val userId: String,
    val lastSyncTime: Long = 0L
)
