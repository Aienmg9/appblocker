package com.Aien.appblocker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_cards")
data class NfcCard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uid: String
)