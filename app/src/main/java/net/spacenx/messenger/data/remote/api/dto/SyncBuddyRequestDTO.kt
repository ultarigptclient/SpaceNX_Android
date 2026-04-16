package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncBuddyRequestDTO(
    val userId: String,
    val lastSyncTime: Long = 0L,
    val buddyEventOffset: Long = 0L
)
