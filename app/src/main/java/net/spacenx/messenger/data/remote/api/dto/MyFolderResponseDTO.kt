package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MyFolderResponseDTO(
    val errorCode: Int,
    val errorMessage: String? = null,
    val buddyList: List<BuddyDTO>? = null
)

@Serializable
data class BuddyDTO(
    val buddyId: String? = null,
    val buddyParent: String? = null,
    val buddyName: String? = null,
    val buddyType: String? = null,
    val buddyOrder: String? = null
)