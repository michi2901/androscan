package com.androscan.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ScanEntry)

    @Query("SELECT * FROM scan_entries ORDER BY capturedAt DESC")
    fun getAll(): Flow<List<ScanEntry>>

    @Query("SELECT COUNT(*) FROM scan_entries")
    fun count(): Flow<Int>

    @Query("DELETE FROM scan_entries")
    suspend fun deleteAll()
}
