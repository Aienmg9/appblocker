package com.Aien.appblocker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class BlockedSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val cooldownMinutes: Int,
    val isEnabled: Boolean = true,
    val actionType: Int = 0,
    val targetGroupId: Int? = null,
    val daysOfWeek: String = "1,2,3,4,5,6,7"
)
