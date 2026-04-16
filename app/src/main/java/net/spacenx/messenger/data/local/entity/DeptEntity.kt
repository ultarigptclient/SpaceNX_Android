package net.spacenx.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "depts",
    indices = [Index("parentDept")]
)
data class DeptEntity(
    @PrimaryKey val deptId: String,
    val parentDept: String = "",
    val deptName: String = "",
    val deptOrder: String = "",
    val deptStatus: String = ""
)
