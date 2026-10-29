package com.geriatricare.app.data.bluetooth

import com.geriatricare.app.data.model.BleData
import com.google.gson.JsonParser

/** Missing fields are partial updates; explicit invalid readings clear old values. */
object SensorPacketDecoder {
    fun decode(json: String, current: BleData): BleData {
        val packet = JsonParser.parseString(json).asJsonObject
        // Optional flags keep compatibility with older/partial packets.
        // Explicit false or malformed contact must override even plausible values.
        val opticalUnavailable = packet.has("finger") &&
            runCatching { packet["finger"].asBoolean }.getOrDefault(false) != true
        val leadOff = packet.has("leadOff") &&
            runCatching { packet["leadOff"].asBoolean }.getOrDefault(true)
        fun vital(key: String, previous: Float, valid: (Float) -> Boolean): Float {
            if (!packet.has(key)) return previous
            val value = runCatching { packet[key].asFloat }.getOrNull()
            return value?.takeIf(valid) ?: 0f
        }
        val ecg = if (leadOff) -1 else if (!packet.has("ecg")) current.ecg else
            runCatching { packet["ecg"].asInt }.getOrNull()?.takeIf(SensorReadingValidator::isValidEcg) ?: -1
        return BleData(ecg, if (opticalUnavailable) 0f else vital("spo2", current.spo2, SensorReadingValidator::isValidSpO2),
            if (opticalUnavailable) 0f else vital("hr", current.hr, SensorReadingValidator::isValidHeartRate),
            vital("temp", current.temp, SensorReadingValidator::isValidTemperature))
    }
}
