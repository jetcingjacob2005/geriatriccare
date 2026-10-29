package com.geriatricare.app.data.model

import kotlin.math.sqrt

/**
 * Result of analyzing a rolling window of raw ECG samples (0-1023 scale,
 * -1 = lead-off) coming from the AD8232 sensor.
 */
enum class EcgStatus { COLLECTING, LEAD_OFF, FLATLINE, NOISY, IRREGULAR, NORMAL }

data class EcgAnalysis(
    val status         : EcgStatus,
    val message        : String,
    val estimatedBpm   : Int? = null,
    /** Coefficient of variation of peak-to-peat spacing (std/mean of RR
     *  intervals) — a simple beat-to-beat irregularity measure, null until
     *  at least 2 peaks have been detected. Also fed into RiskClassifier
     *  as a 4th (ECG-derived) model input alongside HR/SpO2/temp. */
    val rrVariabilityRatio: Double? = null,
    val measurements: EcgMeasurements? = null
) {
    val level: VitalLevel get() = when (status) {
        EcgStatus.NORMAL, EcgStatus.COLLECTING -> VitalLevel.NORMAL
        EcgStatus.IRREGULAR, EcgStatus.NOISY    -> VitalLevel.WARNING
        // Sensor failure must not be labelled a confirmed patient emergency.
        EcgStatus.LEAD_OFF, EcgStatus.FLATLINE  -> VitalLevel.WARNING
    }

    /** Bradycardia/tachycardia flag for the ECG-derived rate, using the same
     *  60–100 bpm resting normal range as the HR sensor reading. This is a
     *  separate concept from [status]/[level] above, which is about rhythm
     *  *regularity* (steady vs. irregular timing), not the rate itself. */
    val rateLevel: VitalLevel? get() = estimatedBpm?.let { it.toFloat().heartRateLevel() }
}

/**
 * Lightweight, on-device ECG heuristic — not a diagnostic-grade analysis.
 * Looks at the same rolling buffer that feeds the waveform chart and flags:
 *   - LEAD_OFF   : electrode pad not making contact (AD8232 LO+/LO- high)
 *   - FLATLINE   : signal present but essentially no variation (no real
 *                  waveform — likely no lead attached to a body, or fault)
 *   - IRREGULAR  : a beat rate estimated from peak spacing falls outside
 *                  a normal resting range, or beat-to-beat spacing is
 *                  too inconsistent
 *   - NORMAL     : peaks detected at a fairly steady, plausible rate
 */
fun List<Float>.analyzeEcg(sampleRateHz: Float = 50f): EcgAnalysis {
    if (sampleRateHz >= 250f) {
        // Preserve the production XGBoost's legacy 50 Hz feature extraction.
        // New morphology stays separate until clinically benchmarked/retrained.
        val stride = (sampleRateHz / 50f).toInt()
        val contiguous = takeLastWhile { it.isFinite() && it in 0f..1023f }
        val legacy = if (lastOrNull()?.let { it < 0 || !it.isFinite() || it > 1023 } == true)
            listOf(last()).analyzeEcg(50f)
        else if (isEcgClipped(contiguous)) EcgAnalysis(EcgStatus.NOISY, "Unusable ECG signal")
        else contiguous.filterIndexed { i, _ -> i % stride == 0 }.takeLast(250).analyzeEcg(50f)
        return legacy.copy(measurements = EcgMeasurementEngine.measure(this, sampleRateHz.toInt()))
    }
    if (lastOrNull()?.let { it < 0f } == true) {
        return EcgAnalysis(EcgStatus.LEAD_OFF, "Electrode not detected — check pad contact")
    }
    if (lastOrNull()?.let { !it.isFinite() || it > 1023f } == true) {
        return EcgAnalysis(EcgStatus.NOISY, "Invalid ECG signal — check sensor and wiring")
    }
    // Never join samples across a contact gap: this invents beat spacing.
    val valid = takeLastWhile { it.isFinite() && it in 0f..1023f }
    if (valid.size < 100) {
        return EcgAnalysis(EcgStatus.COLLECTING, "Collecting ECG data…")
    }

    if (isEcgClipped(valid)) {
        return EcgAnalysis(EcgStatus.NOISY, "ECG signal is clipped — check electrodes, power and wiring")
    }

    val mean = valid.average().toFloat()
    val variance = valid.sumOf { ((it - mean) * (it - mean)).toDouble() } / valid.size
    val stdDev = sqrt(variance).toFloat()

    // A real ECG waveform has a fair amount of swing between baseline and
    // the R-peak. A near-flat signal (very low variation) means no
    // meaningful cardiac signal is being picked up.
    if (stdDev < 12f) {
        return EcgAnalysis(EcgStatus.FLATLINE, "Weak or no signal — check electrode placement")
    }

    // Simple peak detection: a sample counts as a beat if it rises a good
    // margin above the mean and above its neighbors, with a refractory
    // gap so one beat isn't counted twice.
    val threshold = mean + stdDev * 0.8f
    val minGapSamples = (sampleRateHz * 0.3f).toInt().coerceAtLeast(1) // ~300ms refractory
    val peakIndices = mutableListOf<Int>()
    var i = 1
    while (i < valid.size - 1) {
        if (valid[i] > threshold && valid[i] >= valid[i - 1] && valid[i] >= valid[i + 1]) {
            peakIndices.add(i)
            i += minGapSamples
        } else {
            i++
        }
    }

    if (peakIndices.size < 2) {
        return EcgAnalysis(EcgStatus.COLLECTING, "Analyzing rhythm…")
    }

    val gaps = peakIndices.zipWithNext { a, b -> (b - a).toFloat() }
    val avgGapSamples = gaps.average().toFloat()
    val estimatedBpm = ((sampleRateHz * 60f) / avgGapSamples).toInt()

    // Beat-to-beat variability: how consistent is the spacing between peaks?
    val gapMean = gaps.average()
    val gapVariance = gaps.sumOf { (it - gapMean) * (it - gapMean) } / gaps.size
    val gapStdDev = sqrt(gapVariance)
    val gapVariabilityRatio = if (gapMean > 0) gapStdDev / gapMean else 0.0

    return when {
        estimatedBpm !in 40..140 ->
            EcgAnalysis(EcgStatus.IRREGULAR, "Beat rate outside normal range", estimatedBpm, gapVariabilityRatio)
        gapVariabilityRatio > 0.25 ->
            EcgAnalysis(EcgStatus.IRREGULAR, "Irregular beat-to-beat timing", estimatedBpm, gapVariabilityRatio)
        else ->
            EcgAnalysis(EcgStatus.NORMAL, "Rhythm looks steady", estimatedBpm, gapVariabilityRatio)
    }
}

fun isEcgClipped(points: List<Float>): Boolean {
    val valid = points.takeLastWhile { it.isFinite() && it in 0f..1023f }
    return valid.size >= 2 && valid.count { it <= 1f || it >= 1022f }.toFloat() / valid.size > 0.10f
}

/** Raw acquisition is not rhythm classification. Small/constant signals may
 * be inspected, but must not imply a detected heartbeat or verified contact. */
@Suppress("UNUSED_PARAMETER") // Retained call-site API; analysis no longer gates raw display.
fun canDisplayEcg(points: List<Float>, analysis: EcgAnalysis): Boolean {
    val contiguous = points.takeLastWhile { it.isFinite() && it in 0f..1023f }
    // Do not animate saturated voltage as if it were a usable patient ECG.
    // The chart still explains the quality failure; never manufacture a trace.
    return contiguous.size >= 2 && !isEcgClipped(contiguous)
}
