package com.geriatricare.app.data.model

/**
 * Live BLE data parsed from JSON sent by Arduino:
 * {"ecg":512,"spo2":98.0,"hr":72.0,"temp":36.6}
 * Location is deliberately not part of the sensor packet. The carried phone
 * publishes its own fused location through PatientLocationService.
 */
data class BleData(
    val ecg: Int   = -1,
    val spo2: Float = 0f,
    val hr: Float   = 0f,
    val temp: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleData) return false
        // Emit contact transitions without rerendering all vitals at 250 Hz.
        return (ecg >= 0) == (other.ecg >= 0) && hr == other.hr &&
                spo2 == other.spo2 &&
                temp == other.temp
    }

    override fun hashCode(): Int {
        var result = hr.hashCode()
        result = 31 * result + spo2.hashCode()
        result = 31 * result + temp.hashCode()
        result = 31 * result + (ecg >= 0).hashCode()
        return result
    }
}

enum class ConnectionStatus {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED
}

data class BleConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceName: String = "",
    val deviceAddress: String = ""
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED
    val isScanning: Boolean  get() = status == ConnectionStatus.SCANNING
}

/**
 * Alert level based on vital sign thresholds.
 */
enum class VitalLevel { NORMAL, WARNING, CRITICAL }

fun Float.spO2Level(): VitalLevel = when {
    this >= 95f -> VitalLevel.NORMAL   // 95–100% is the normal resting range at sea level
    this >= 90f -> VitalLevel.WARNING
    else        -> VitalLevel.CRITICAL
}

fun Float.heartRateLevel(): VitalLevel = when {
    this in 60f..100f -> VitalLevel.NORMAL   // normal resting heart rate
    this in 50f..120f -> VitalLevel.WARNING  // mild bradycardia/tachycardia
    else              -> VitalLevel.CRITICAL // marked bradycardia/tachycardia
}

fun Float.temperatureLevel(): VitalLevel = when {
    this > 37f -> VitalLevel.CRITICAL
    else       -> VitalLevel.NORMAL
}
