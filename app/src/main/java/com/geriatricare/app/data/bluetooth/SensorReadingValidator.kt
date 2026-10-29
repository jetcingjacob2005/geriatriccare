package com.geriatricare.app.data.bluetooth

/**
 * Guards the application boundary against corrupted or implausible BLE data.
 * These are transport-safety limits, not diagnostic ranges: out-of-range data
 * is treated as unavailable rather than being shown or persisted as a vital.
 */
object SensorReadingValidator {
    fun isValidEcg(value: Int): Boolean = value == -1 || value in 0..1023
    fun isValidHeartRate(value: Float): Boolean = value.isFinite() && value in 25f..230f
    fun isValidSpO2(value: Float): Boolean = value.isFinite() && value in 50f..100f
    fun isValidTemperature(value: Float): Boolean = value.isFinite() && value in 25f..43f
}
