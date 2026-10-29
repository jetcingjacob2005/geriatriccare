package com.geriatricare.app.data.ml

import com.geriatricare.app.data.bluetooth.SensorReadingValidator
import com.geriatricare.app.data.model.BleData

/** Readiness is separate from risk. Never impute healthy readings to obtain a score. */
object ScreeningReadiness {
    /** Keep up to 30 minutes of valid observations for progressively steadier statistics. */
    const val MAX_HISTORY_SAMPLES = 1500
    /** Allow an initial estimate as soon as one complete validated observation exists. */
    const val MIN_PREDICTION_SAMPLES = 1

    fun missingVitals(data: BleData): List<String> = buildList {
        if (!SensorReadingValidator.isValidHeartRate(data.hr)) add("heart rate")
        if (!SensorReadingValidator.isValidSpO2(data.spo2)) add("SpO₂")
        if (!SensorReadingValidator.isValidTemperature(data.temp)) add("temperature")
    }
    fun status(count: Int, ecgBpm: Int?, rr: Double?, ecgMessage: String): String? {
        val ecgReady = ecgBpm != null && ecgBpm in 25..230 && rr != null && rr.isFinite() && rr in 0.0..2.0
        val warmup = if (count < MIN_PREDICTION_SAMPLES) "Waiting for a complete live sensor reading." else null
        val ecg = if (!ecgReady) "ECG timing unavailable: $ecgMessage. A waveform number alone is not a detected heartbeat." else null
        return listOfNotNull(warmup, ecg).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun resultStatus(count: Int): String = when {
        count >= MAX_HISTORY_SAMPLES -> "30-minute live trend window analysed"
        count <= 1 -> "Immediate provisional estimate from the first complete live reading"
        else -> "Live estimate from $count readings · updating as new data arrives"
    }
}
