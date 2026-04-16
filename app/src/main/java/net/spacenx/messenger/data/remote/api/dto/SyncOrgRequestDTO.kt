package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncOrgRequestDTO(
    val userId: String,
    val lastSyncTime: Long,
    val orgEventOffset: Long = 0L
)