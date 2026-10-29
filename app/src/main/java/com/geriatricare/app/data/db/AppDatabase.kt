package com.geriatricare.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.geriatricare.app.data.model.VitalReading

@Database(
    entities = [VitalReading::class],
    version  = 2, // Legacy v2 location columns remain for schema compatibility; new location uses Patient.phoneLocation.
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vitalReadingDao(): VitalReadingDao
}
