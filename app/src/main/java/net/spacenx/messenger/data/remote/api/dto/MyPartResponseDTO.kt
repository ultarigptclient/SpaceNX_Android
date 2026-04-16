package net.spacenx.messenger.data.remote.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MyPartResponseDTO(
    val errorCode: Int,
    val pathDepts: List<DeptDTO>? = null
)

@Serializable
data class DeptDTO(
    val deptKey: String? = null,
    val deptName: String? = null,
    val parentKey: String? = null
)