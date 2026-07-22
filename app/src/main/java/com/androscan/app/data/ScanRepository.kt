package com.androscan.app.data

import kotlinx.coroutines.flow.Flow

class ScanRepository(private val dao: ScanDao) {
    fun getAll(): Flow<List<ScanEntry>> = dao.getAll()
    fun count(): Flow<Int> = dao.count()
    suspend fun insert(entry: ScanEntry) = dao.insert(entry)
    suspend fun deleteAll() = dao.deleteAll()
}
