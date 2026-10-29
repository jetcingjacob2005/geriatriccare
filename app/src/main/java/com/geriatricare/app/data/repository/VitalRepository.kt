package com.geriatricare.app.data.repository

import com.geriatricare.app.data.db.VitalReadingDao
import com.geriatricare.app.data.model.VitalReading
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VitalRepository @Inject constructor(
    private val dao: VitalReadingDao
) {
    fun getReadings(patientId: String): Flow<List<VitalReading>> = dao.getReadingsForPatient(patientId)

    fun getReadingsSince(patientId: String, fromMs: Long): Flow<List<VitalReading>> =
        dao.getReadingsSince(patientId, fromMs)

    suspend fun save(reading: VitalReading) = dao.insert(reading)

    suspend fun getLatest(patientId: String): VitalReading? = dao.getLatestReading(patientId)

    suspend fun count(patientId: String): Int = dao.countForPatient(patientId)
    suspend fun getRange(patientId: String, start: Long, end: Long, limit: Int = 6000) = dao.getRange(patientId, start, end, limit)
    suspend fun countRange(patientId: String, start: Long, end: Long) = dao.countRange(patientId, start, end)
}
