package com.geriatricare.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single snapshot of all sensor readings for one patient,
 * persisted locally in Room DB.
 */
@Entity(tableName = "vital_readings")
data class VitalReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val patientId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val ecg: Int = 0,           // Raw ADC value (0-1023) from AD8232
    val spO2: Float = 0f,       // Oxygen saturation % from MAX30102
    val heartRate: Float = 0f,  // BPM from MAX30102
    val temperature: Float = 0f, // °C from DS18B20
    // Retained only to read the existing Room v2 schema. New sensor readings
    // never populate these retired hardware-GPS columns.
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val hasFix: Boolean = false
)
