package com.geriatricare.app.data.model

import kotlin.math.*

/** Experimental single-lead measurements; never a diagnosis or calibrated voltage.
 * Confidence is a quality gate, NOT a calibrated medical probability. */
data class EcgMeasurements(
    val sampleRateHz: Int,
    val seconds: Double,
    val beatCount: Int = 0,
    val heartRateBpm: Double? = null,
    val rrMs: Double? = null,
    val sdnnMs: Double? = null,
    val rmssdMs: Double? = null,
    val prMs: Double? = null,
    val qrsMs: Double? = null,
    val pWaveMs: Double? = null,
    val qtMs: Double? = null,
    val qtcFridericiaMs: Double? = null,
    val delineatedBeats: Int = 0,
    val quality: String = "Insufficient signal"
)

/** Conservative morphology prototype. No clinical threshold classification.
 * Must be benchmarked on annotated ECGs and this AD8232 before clinical use.
 * Does not modify displayed/stored ADC values or the XGBoost feature contract. */
object EcgMeasurementEngine {
    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 0) (s[n / 2 - 1] + s[n / 2]) / 2 else s[n / 2]
    }
    private fun meanFilter(x: List<Double>, radius: Int): List<Double> {
        val sum = DoubleArray(x.size + 1)
        x.forEachIndexed { i, v -> sum[i + 1] = sum[i] + v }
        return x.indices.map { i ->
            val a = max(0, i - radius); val b = min(x.size, i + radius + 1)
            (sum[b] - sum[a]) / (b - a)
        }
    }
    // 50 Hz notch for a 250/500 Hz stream; inference-only, not the raw chart.
    private fun notch(x: List<Double>, fs: Int): List<Double> {
        val w = 2 * PI * 50 / fs; val alpha = sin(w) / 60
        val a0 = 1 + alpha; val b0 = 1 / a0; val b1 = -2 * cos(w) / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return x.map { v ->
            val y = b0 * v + b1 * x1 + b0 * x2 - b1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y; y
        }
    }

    fun measure(points: List<Float>, fs: Int): EcgMeasurements {
        val raw = points.takeLastWhile { it.isFinite() && it in 0f..1023f }
        val empty = EcgMeasurements(fs, if (fs > 0) raw.size.toDouble() / fs else 0.0)
        if (fs !in listOf(50, 250, 500) || raw.size < fs * 4) return empty
        if (isEcgClipped(raw)) return empty.copy(quality = "Clipped")
        val dc = median(raw.map { it.toDouble() })
        val centred = raw.map { it - dc }
        val filtered = if (fs >= 250) meanFilter(notch(centred, fs), max(1, fs / 250)) else centred
        // A rolling mean spreads the large QRS into the smaller P/T waves.
        // Median baseline avoids that averaging artefact (still experimental).
        val radius = fs / 2
        val baseline = filtered.indices.map { i ->
            median(filtered.subList(max(0, i - radius), min(filtered.size, i + radius + 1)))
        }
        val x = filtered.indices.map { filtered[it] - baseline[it] }
        val differences = x.zipWithNext { a, b -> abs(b - a) }
        val noise = max(0.5, median(differences) / 0.954)
        val amplitude = x.map { abs(it) }
        val threshold = max(8.0, max(median(amplitude) * 4, noise * 6))
        val refractory = (fs * 0.28).toInt()
        val candidates = (1 until x.lastIndex).filter {
            amplitude[it] > threshold && amplitude[it] >= amplitude[it - 1] && amplitude[it] > amplitude[it + 1]
        }
        val peaks = mutableListOf<Int>()
        for (i in candidates) {
            if (peaks.isEmpty() || i - peaks.last() >= refractory) peaks.add(i)
            else if (amplitude[i] > amplitude[peaks.last()]) peaks[peaks.lastIndex] = i
        }
        if (peaks.size < 4) return empty.copy(quality = "Beat detection pending")
        val rr = peaks.zipWithNext { a, b -> (b - a) * 1000.0 / fs }
        // Do not silently discard difficult beats and report a healthy result.
        if (rr.any { it !in 280.0..2400.0 }) return empty.copy(quality = "Unreliable beat timing")
        val peakStrength = median(peaks.map { amplitude[it] }) / noise
        if (peakStrength < 10) return empty.copy(quality = "Low signal quality")
        val rrMean = rr.average()
        val sdnn = sqrt(rr.sumOf { (it - rrMean).pow(2) } / (rr.size - 1))
        val rmssd = sqrt(rr.zipWithNext { a, b -> (b - a).pow(2) }.average())
        val rhythm = empty.copy(beatCount = peaks.size, heartRateBpm = 60000 / rrMean,
            rrMs = rrMean, sdnnMs = sdnn, rmssdMs = rmssd,
            quality = if (fs < 250) "Legacy sampling: rhythm only" else "Rhythm available")
        if (fs < 250) return rhythm // Never invent fine intervals from 20 ms samples.

        data class Wave(val start: Int, val end: Int)
        fun boundary(peak: Int, a: Int, b: Int, fraction: Double): Wave? {
            if (a < 0 || b >= x.size || a >= peak || peak >= b) return null
            val cut = max(noise * 3, amplitude[peak] * fraction)
            if (amplitude[peak] < noise * 10) return null
            val quiet = max(2, (fs * 0.012).toInt())
            fun quietAt(i: Int) = (i until i + quiet).all { it in x.indices && amplitude[it] <= cut }
            var left: Int? = null; var right: Int? = null
            for (i in peak - quiet downTo a) if (quietAt(i)) { left = i + quiet; break }
            for (i in peak + 1..b - quiet + 1) if (quietAt(i)) { right = i; break }
            if (left == null || right == null || right <= left) return null
            return Wave(left, right)
        }
        fun smallWave(a: Int, b: Int): Wave? {
            if (a < 0 || b >= x.size || b - a < fs * 0.04) return null
            val peak = (a..b).maxBy { amplitude[it] }
            if (peak - a < fs * 0.016 || b - peak < fs * 0.016) return null
            // Reject an ambiguous second deflection rather than guessing P/T.
            if ((a + 1 until b).any { abs(it - peak) > fs * 0.08 &&
                    amplitude[it] > amplitude[peak] * 0.8 && amplitude[it] >= amplitude[it - 1] &&
                    amplitude[it] > amplitude[it + 1] }) return null
            return boundary(peak, a, b, 0.15)
        }
        val qrsValues = mutableListOf<Double>(); val pValues = mutableListOf<Double>()
        val prValues = mutableListOf<Double>(); val qtValues = mutableListOf<Double>()
        for (k in 1 until peaks.lastIndex) {
            val r = peaks[k]
            val qrs = boundary(r, r - (fs * 0.14).toInt(), r + (fs * 0.16).toInt(), 0.10) ?: continue
            val qrsMs = (qrs.end - qrs.start) * 1000.0 / fs
            if (qrsMs !in 24.0..200.0) continue // Detection bounds, NOT normal ranges.
            qrsValues.add(qrsMs)
            val p = smallWave(max(peaks[k - 1] + (fs * 0.45).toInt(), r - (fs * 0.32).toInt()),
                qrs.start - (fs * 0.024).toInt())
            if (p != null) {
                val pMs = (p.end - p.start) * 1000.0 / fs
                val prMs = (qrs.start - p.start) * 1000.0 / fs
                if (pMs in 24.0..180.0 && prMs in 60.0..350.0) { pValues.add(pMs); prValues.add(prMs) }
            }
            val t = smallWave(qrs.end + (fs * 0.032).toInt(),
                min(r + (fs * 0.55).toInt(), peaks[k + 1] - (fs * 0.20).toInt()))
            if (t != null) {
                val qt = (t.end - qrs.start) * 1000.0 / fs
                if (qt in 180.0..650.0) qtValues.add(qt)
            }
        }
        // Need >=3 consistent, complete beats. Missing/uncertain remains null.
        fun consistent(v: List<Double>): Double? {
            if (v.size < 3) return null
            val m = median(v)
            return m.takeIf { v.all { abs(it - m) <= max(20.0, m * 0.15) } }
        }
        val qt = consistent(qtValues)
        // QTc withheld for unstable RR; Fridericia uses seconds for RR.
        val qtc = qt?.takeIf { sdnn / rrMean < 0.10 }?.div((rrMean / 1000).pow(1.0 / 3))
        return rhythm.copy(qrsMs = consistent(qrsValues), prMs = consistent(prValues),
            pWaveMs = consistent(pValues), qtMs = qt, qtcFridericiaMs = qtc,
            delineatedBeats = qrsValues.size, quality = "Experimental morphology")
    }
}
