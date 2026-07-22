package com.androscan.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_entries")
data class ScanEntry(
    @PrimaryKey val id: String,
    val barcode: String,
    val articleCode: String,
    val capturedAt: Long
)
