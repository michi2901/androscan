package com.androscan.app.data

import kotlinx.coroutines.flow.Flow

class ScanRepository(private val dao: ScanDao) {
    fun getAll(): Flow<List<ScanEntry>> = dao.getAll()
    fun count(): Flow<Int> = dao.count()
    suspend fun insert(entry: ScanEntry) = dao.insert(entry)
    suspend fun markAllSentByMail() = dao.markAllSentByMail()
    suspend fun deleteOlderThan(cutoffMillis: Long) = dao.deleteOlderThan(cutoffMillis)
    suspend fun deleteAll() = dao.deleteAll()
}
