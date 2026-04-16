package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "attachs",
    primaryKeys = ["messageCode", "fileName"],
    indices = [Index("messageCode")]
)
data class AttachEntity(
    val messageCode: String,
    val filePath: String = "",
    val fileName: String = "",
    val fileLength: Long = 0L,
    val lastModifiedDate: Long = 0L,
    val originalFileName: String = "",
    val fileId: String = ""
)
