package com.geriatricare.app.data.model

import com.google.gson.Gson

/** Explicit user-exported short window. No patient identity or invented voltage. */
data class EcgSnapshot(
    val formatVersion: Int = 1,
    val capturedAtPhoneMs: Long,
    val sampleRateHz: Int,
    val durationSeconds: Double,
    val units: String = "ADC counts (0–1023), not calibrated mV",
    val scope: String = "Raw short-window snapshot, not a diagnostic ECG or continuous recording",
    val samples: List<Float>
) {
    companion object {
        fun json(points: List<Float>, rate: Int, capturedAtMs: Long): String {
            require(rate in listOf(50,250,500))
            require(points.size in 2..rate * 6)
            require(points.all { it.isFinite() && it in 0f..1023f })
            return Gson().toJson(EcgSnapshot(capturedAtPhoneMs = capturedAtMs,
                sampleRateHz = rate, durationSeconds = points.size.toDouble() / rate, samples = points.toList()))
        }
    }
}
