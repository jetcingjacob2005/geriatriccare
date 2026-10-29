package com.geriatricare.app.data.model

/** Display-only history. Rhythm analysis keeps its independent high-resolution buffer. */
object EcgDisplayWindow {
    // Six seconds matches a conventional phone-width rhythm strip and gives
    // each real beat enough horizontal space to remain readable.
    // Acquisition and analysis remain independent of this display history.
    const val DISPLAY_SECONDS = 6
    const val MAX_SAMPLES = 300 // Six seconds for the legacy 50 Hz stream.

    fun maxSamples(sampleRateHz: Int): Int =
        sampleRateHz.coerceIn(1, 500) * DISPLAY_SECONDS

    fun append(points: List<Float>, sample: Float): List<Float> =
        if (!sample.isFinite() || sample !in 0f..1023f) listOf(-1f)
        else (points.takeLast(MAX_SAMPLES - 1) + sample)

    /**
     * A very small, zero-phase smoothing step used by the chart only.
     *
     * AD8232 output commonly contains ADC jitter and slow motion-related baseline drift.
     * A weighted five-sample pass followed by a slow-baseline correction makes the phone
     * trace easier to read without changing stored samples, ECG analysis, export, alerts,
     * or risk calculation. It never reconstructs or inserts beats.
     */
    fun smoothForDisplay(points: List<Float>, sampleRateHz: Int = 50): List<Float> {
        if (points.size < 5) return points
        // A lead-off or corrupt ADC sample is a gap. Never smooth across it:
        // blending the two sides could draw a wave where none was measured.
        if (points.any { !it.isFinite() || it !in 0f..1023f }) {
            val result = ArrayList<Float>(points.size)
            var index = 0
            while (index < points.size) {
                if (!points[index].isFinite() || points[index] !in 0f..1023f) {
                    result.add(points[index++])
                    continue
                }
                val start = index
                while (index < points.size && points[index].isFinite() && points[index] in 0f..1023f) index++
                result.addAll(smoothForDisplay(points.subList(start, index), sampleRateHz))
            }
            return result
        }
        val weights = intArrayOf(1, 2, 4, 2, 1)
        val smoothed = points.indices.map { index ->
            if (index < 2 || index > points.lastIndex - 2) {
                points[index]
            } else {
                var weightedSum = 0f
                var weightTotal = 0
                for (offset in -2..2) {
                    val sample = points[index + offset]
                    if (sample.isFinite() && sample in 0f..1023f) {
                        val weight = weights[offset + 2]
                        weightedSum += sample * weight
                        weightTotal += weight
                    }
                }
                if (weightTotal == 0) points[index] else weightedSum / weightTotal
            }
        }

        // Remove only slow display drift using a centred ~1.2 second baseline.
        // Wait for two baseline windows so chart startup is not distorted.
        val baselineSamples = (sampleRateHz.coerceIn(1, 500) * 1.2f).toInt().coerceAtLeast(5)
        if (smoothed.size < baselineSamples * 2) return smoothed
        val prefix = DoubleArray(smoothed.size + 1)
        for (index in smoothed.indices) prefix[index + 1] = prefix[index] + smoothed[index]
        val centre = smoothed.sorted()[smoothed.size / 2]
        val radius = baselineSamples / 2
        return smoothed.indices.map { index ->
            val start = (index - radius).coerceAtLeast(0)
            val endExclusive = (index + radius + 1).coerceAtMost(smoothed.size)
            val localBaseline = ((prefix[endExclusive] - prefix[start]) / (endExclusive - start)).toFloat()
            (centre + smoothed[index] - localBaseline).coerceIn(0f, 1023f)
        }
    }

    /** Fixed time scale, including startup: never stretch a few samples to full width. */
    fun x(index: Int, count: Int, width: Float, maxSamples: Int = MAX_SAMPLES): Float =
        (maxSamples - count + index) * width / (maxSamples - 1)

    /** Display only: centre offset voltage, cap zoom at 2x the full ADC span.
     * No per-beat normalization, synthetic waves, or clinical mV calibration.
     */
    fun bounds(points: List<Float>): Pair<Float, Float> {
        val valid = points.filter { it.isFinite() && it in 0f..1023f }
        if (valid.isEmpty()) return 0f to 1023f
        val low = valid.min()
        val high = valid.max()
        val centre = (low + high) / 2f
        val span = ((high - low) * 1.36f).coerceAtLeast(512f)
        return (centre - span / 2f) to (centre + span / 2f)
    }
}
