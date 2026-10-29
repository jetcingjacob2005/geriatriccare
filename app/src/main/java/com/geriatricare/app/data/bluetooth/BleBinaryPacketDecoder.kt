package com.geriatricare.app.data.bluetooth

/** Decoder for the fixed-size ESP32-C3 packets. Keeping every packet at or
 * below the default 20-byte BLE payload prevents JSON-fragment loss. */
object BleBinaryPacketDecoder {
    sealed interface Packet

    data class Ecg(
        val sequence: Int,
        val timingValid: Boolean,
        val samples: List<Float>
    ) : Packet

    data class Vitals(
        val heartRate: Float,
        val spo2: Float,
        val temperature: Float,
        val fingerPresent: Boolean,
        val max30102Ready: Boolean,
        val leadOff: Boolean,
        val latestIr: Long
    ) : Packet

    fun decode(bytes: ByteArray): Packet? = when {
        bytes.size == 20 && bytes.u8(0) == 0xE1 -> decodeEcg(bytes)
        bytes.size == 12 && bytes.u8(0) == 0xD1 -> decodeVitals(bytes)
        else -> null
    }

    private fun decodeEcg(bytes: ByteArray): Ecg? {
        val values = (0 until 8).map { bytes.i16(4 + it * 2).toFloat() }
        if (values.any { it != -1f && it !in 0f..1023f }) return null
        return Ecg(
            sequence = bytes.u16(2),
            timingValid = bytes.u8(1) and 0x01 != 0,
            samples = values
        )
    }

    private fun decodeVitals(bytes: ByteArray): Vitals {
        val flags = bytes.u8(1)
        return Vitals(
            heartRate = bytes.u16(2) / 10f,
            spo2 = bytes.u16(4) / 10f,
            temperature = bytes.i16(6) / 10f,
            fingerPresent = flags and 0x01 != 0,
            max30102Ready = flags and 0x02 != 0,
            leadOff = flags and 0x04 != 0,
            latestIr = bytes.u8(8).toLong() or
                (bytes.u8(9).toLong() shl 8) or
                (bytes.u8(10).toLong() shl 16) or
                (bytes.u8(11).toLong() shl 24)
        )
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.i16(offset: Int): Int = u16(offset).let { if (it >= 0x8000) it - 0x10000 else it }
}
