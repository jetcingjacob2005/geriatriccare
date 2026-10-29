package com.geriatricare.app

import com.geriatricare.app.data.model.EcgDisplayWindow
import org.junit.Assert.*
import org.junit.Test

class EcgDisplayWindowTest {
    @Test fun sixSecondWindowUsesActualSampleRate() {
        assertEquals(1500, EcgDisplayWindow.maxSamples(250))
        assertEquals(300, EcgDisplayWindow.maxSamples(50))
    }

    @Test fun displaySmoothingLeavesEdgesAndSteadySignalsUntouched() {
        val steady = List(9) { 700f }
        val filtered = EcgDisplayWindow.smoothForDisplay(steady)
        assertEquals(steady, filtered)
        assertEquals(700f, filtered.first(), 0f)
        assertEquals(700f, filtered.last(), 0f)
    }

    @Test fun displaySmoothingReducesOnlyAnIsolatedAdcSpike() {
        val raw = listOf(700f, 700f, 700f, 900f, 700f, 700f, 700f)
        val filtered = EcgDisplayWindow.smoothForDisplay(raw)
        assertEquals(raw.first(), filtered.first(), 0f)
        assertEquals(raw.last(), filtered.last(), 0f)
        assertEquals(780f, filtered[3], 0.001f)
        // The original raw list is not mutated and remains available to analysis.
        assertEquals(900f, raw[3], 0f)
    }

    @Test fun displayConditioningReducesSlowBaselineDriftWithoutChangingRawData() {
        val raw = List(800) { index -> 400f + index * 0.25f }
        val filtered = EcgDisplayWindow.smoothForDisplay(raw, 250)
        val rawDrift = raw[600] - raw[200]
        val displayedDrift = filtered[600] - filtered[200]
        assertTrue(kotlin.math.abs(displayedDrift) < kotlin.math.abs(rawDrift) * 0.1f)
        assertEquals(400f, raw.first(), 0f)
        assertEquals(599.75f, raw.last(), 0f)
    }

    @Test fun displayCentresOffsetWithoutExaggeratingTinyNoise() {
        val (low, high) = EcgDisplayWindow.bounds(listOf(799f, 800f, 801f))
        assertEquals(512f, high - low, 0f)
        assertEquals(800f, (low + high) / 2f, 0f)
    }
    @Test fun displayIncludesFullSignalWithoutChangingSamples() {
        val points = listOf(100f, 900f)
        val (low, high) = EcgDisplayWindow.bounds(points)
        assertTrue(low < 100f)
        assertTrue(high > 900f)
        assertEquals(listOf(100f, 900f), points)
    }
    @Test fun retainsLongerHistoryInOriginalOrder() {
        var points = emptyList<Float>()
        repeat(301) { points = EcgDisplayWindow.append(points, it.toFloat()) }
        assertEquals(300, points.size)
        assertEquals(1f, points.first(), 0f)
        assertEquals(300f, points.last(), 0f)
    }
    @Test fun leadOffDropsOldDisplayImmediately() {
        assertEquals(listOf(-1f), EcgDisplayWindow.append(listOf(500f, 510f), -1f))
    }
    @Test fun startupAndFullWindowHaveSameHorizontalSpeed() {
        val startup = EcgDisplayWindow.x(1, 2, 299f) - EcgDisplayWindow.x(0, 2, 299f)
        val full = EcgDisplayWindow.x(1, 300, 299f) - EcgDisplayWindow.x(0, 300, 299f)
        assertEquals(1f, startup, 0f)
        assertEquals(startup, full, 0f)
        assertEquals(299f, EcgDisplayWindow.x(299, 300, 299f), 0f)
        assertEquals(0f, EcgDisplayWindow.x(0, 300, 299f), 0f)
    }
}
