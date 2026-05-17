package com.Aien.appblocker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_whitelist")
data class FocusWhitelist(
    @PrimaryKey val packageName: String
)
