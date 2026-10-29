package com.geriatricare.app.data.bluetooth

import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates realistic mock sensor data for demo / offline testing.
 * Simulates a synthetic ECG using P-QRS-T wave components.
 */
class MockDataGenerator {

    private var phase = 0.0
    private val dt = 0.02          // 50 Hz ECG sampling

    // ── ECG ──────────────────────────────────────────────────────────────────

    fun nextEcgSample(): Int {
        phase += dt
        if (phase > 1.0) phase -= 1.0
        val normalized = generateEcgPoint(phase)
        return (normalized * 400 + 512).toInt().coerceIn(0, 1023)
    }

    /** P-QRS-T wave model in the range [-1, 3] */
    private fun generateEcgPoint(p: Double): Double = when {
        p < 0.05  -> 0.0
        p < 0.10  -> -0.3 * sin(PI * (p - 0.05) / 0.05)   // P wave
        p < 0.18  -> 0.0
        p < 0.20  -> -0.5 * sin(PI * (p - 0.18) / 0.02)   // Q dip
        p < 0.22  ->  3.0 * sin(PI * (p - 0.20) / 0.02)   // R spike
        p < 0.26  -> -0.6 * sin(PI * (p - 0.22) / 0.04)   // S wave
        p < 0.38  -> 0.0
        p < 0.58  ->  0.5 * sin(PI * (p - 0.38) / 0.20)   // T wave
        else      -> 0.0
    }

    // ── Other vitals (slow drift simulation) ─────────────────────────────────

    private var spO2Base = 97.5f
    private var hrBase   = 72f
    private var tempBase = 36.5f

    fun nextSpO2(): Float {
        spO2Base += (Math.random() * 0.4 - 0.2).toFloat()
        spO2Base = spO2Base.coerceIn(94f, 100f)
        return spO2Base
    }

    fun nextHR(): Float {
        hrBase += (Math.random() * 2 - 1).toFloat()
        hrBase = hrBase.coerceIn(60f, 90f)
        return hrBase
    }

    fun nextTemp(): Float {
        tempBase += (Math.random() * 0.1 - 0.05).toFloat()
        tempBase = tempBase.coerceIn(36.0f, 37.2f)
        return (tempBase * 10).toInt() / 10f   // 1 decimal
    }
}
