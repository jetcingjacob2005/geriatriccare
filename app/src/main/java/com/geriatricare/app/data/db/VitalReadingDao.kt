package com.geriatricare.app.data.db

import androidx.room.*
import com.geriatricare.app.data.model.VitalReading
import kotlinx.coroutines.flow.Flow

@Dao
interface VitalReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: VitalReading)

    @Query("SELECT * FROM vital_readings WHERE patientId = :patientId ORDER BY timestamp DESC LIMIT 200")
    fun getReadingsForPatient(patientId: String): Flow<List<VitalReading>>

    @Query("SELECT * FROM vital_readings WHERE patientId = :patientId AND timestamp >= :from ORDER BY timestamp ASC")
    fun getReadingsSince(patientId: String, from: Long): Flow<List<VitalReading>>

    @Query("SELECT * FROM vital_readings WHERE patientId = :patientId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReading(patientId: String): VitalReading?

    @Query("DELETE FROM vital_readings WHERE patientId = :patientId")
    suspend fun deleteAllForPatient(patientId: String)

    @Query("SELECT COUNT(*) FROM vital_readings WHERE patientId = :patientId")
    suspend fun countForPatient(patientId: String): Int

    @Query("SELECT * FROM vital_readings WHERE patientId = :patientId AND timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getRange(patientId: String, start: Long, end: Long, limit: Int): List<VitalReading>

    @Query("SELECT COUNT(*) FROM vital_readings WHERE patientId = :patientId AND timestamp >= :start AND timestamp < :end")
    suspend fun countRange(patientId: String, start: Long, end: Long): Int
}
