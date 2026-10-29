package com.geriatricare.app.data.bluetooth

import com.google.gson.JsonObject
import com.geriatricare.app.data.model.EcgDisplayWindow
import kotlin.math.abs

/** Bounded, timestamp-aware acquisition; never bridge missing samples. */
class EcgStreamBuffer {
    companion object {
        // Packet-start timestamps are generated on a single-core ESP32-C3.
        // Allow brief BLE/I2C scheduling jitter while sequence numbers still
        // guarantee that no missing ECG samples are silently joined.
        private const val MAX_PACKET_START_JITTER_MS = 20L
    }
    var sampleRateHz = 50
        private set
    var analysis = emptyList<Float>()
        private set
    var display = emptyList<Float>()
        private set
    private var expectedSequence: Long? = null
    private var expectedTimeMs: Long? = null
    private var expectedBinarySequence: Int? = null

    @Synchronized fun clear() {
        analysis = emptyList(); display = emptyList()
        expectedSequence = null; expectedTimeMs = null
        expectedBinarySequence = null
    }

    @Synchronized fun accept(packet: JsonObject): Boolean {
        if (!packet.has("es")) return false
        val frame = runCatching {
            val rate = packet["fs"].asInt
            require(rate in listOf(250, 500))
            require(packet["fs"].asDouble == rate.toDouble())
            val values = packet["es"].asJsonArray.map {
                val value = it.asDouble
                require(value.isFinite() && value % 1.0 == 0.0 && (value == -1.0 || value in 0.0..1023.0))
                value.toFloat()
            }
            require(values.size in 1..25)
            val seq = packet["seq"].asLong
            val time = packet["t"].asLong
            require(seq in 0..0xffffffffL && time in 0..0xffffffffL)
            require(packet["seq"].asDouble == seq.toDouble() && packet["t"].asDouble == time.toDouble())
            require(packet["timing"].asBoolean)
            Triple(rate, values, seq to time)
        }.getOrElse { clear(); return true }
        val (rate, values, stamps) = frame
        val (seq, time) = stamps
        val timeDelta = expectedTimeMs?.let { ((time - it + 0x80000000L) and 0xffffffffL) - 0x80000000L }
        if (rate != sampleRateHz || expectedSequence?.let { it != seq } == true ||
            timeDelta?.let { abs(it) > MAX_PACKET_START_JITTER_MS } == true) clear()
        sampleRateHz = rate
        append(values)
        expectedSequence = (seq + values.size) and 0xffffffffL
        expectedTimeMs = (time + values.size * (1000 / rate)) and 0xffffffffL
        return true
    }

    @Synchronized fun legacy(value: Float) {
        if (sampleRateHz != 50 || expectedSequence != null) clear()
        sampleRateHz = 50
        append(listOf(value))
    }

    /** Accept the compact C3 packet. Reception timing is irrelevant because
     * sequence continuity and the firmware acquisition flag describe the
     * sampled signal itself. Invalid timing resets analysis but the raw, valid
     * ADC waveform remains visible instead of making the chart appear broken. */
    @Synchronized fun acceptBinary(
        values: List<Float>,
        sequence: Int,
        timingValid: Boolean
    ) {
        require(sequence in 0..0xffff)
        require(values.size == 8 && values.all { it == -1f || it in 0f..1023f })
        val continuous = expectedBinarySequence?.let { it == sequence } != false
        if (sampleRateHz != 250 || expectedSequence != null || !continuous) {
            analysis = emptyList()
            display = emptyList()
        }
        sampleRateHz = 250
        expectedSequence = null
        expectedTimeMs = null
        expectedBinarySequence = (sequence + values.size) and 0xffff

        if (values.any { it < 0f }) {
            analysis = emptyList()
            display = emptyList()
            return
        }

        val oldDisplay = display
        display = (oldDisplay + values).takeLast(EcgDisplayWindow.maxSamples(sampleRateHz))
        analysis = if (timingValid && continuous) {
            (analysis + values).takeLast(sampleRateHz * 8)
        } else {
            emptyList()
        }
    }

    private fun append(values: List<Float>) {
        val lastGap = values.indexOfLast { it < 0 || !it.isFinite() || it > 1023 }
        if (lastGap >= 0) {
            analysis = emptyList()
            display = emptyList()
            return
        }
        analysis = (analysis + values).takeLast(if (sampleRateHz == 50) 250 else sampleRateHz * 8)
        display = (display + values).takeLast(EcgDisplayWindow.maxSamples(sampleRateHz))
    }
}
