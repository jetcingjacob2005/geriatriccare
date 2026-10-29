package com.geriatricare.app

import com.geriatricare.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class EcgSignalTest {
    // Synthetic test fixture only; never used as patient data or model training.
    private fun pulses(n: Int) = List(n) { i ->
        val distance = kotlin.math.abs(i % 50 - 25)
        512f + if (distance < 5) (5 - distance) * 40f else 0f
    }
    @Test fun singleLeadOffImmediatelySuppressesOldWaveAndRate() {
        val points = pulses(249) + -1f
        val result = points.analyzeEcg()
        assertEquals(EcgStatus.LEAD_OFF, result.status)
        assertNull(result.estimatedBpm)
        assertFalse(canDisplayEcg(points, result))
        assertFalse(canDisplayEcg(points, EcgAnalysis(EcgStatus.NORMAL, "old result")))
    }
    @Test fun reconnectionShowsNewRawTraceButStillWaitsForRhythm() {
        val points = pulses(150) + -1f + pulses(99)
        val result = points.analyzeEcg()
        assertEquals(EcgStatus.COLLECTING, result.status)
        assertNull(result.estimatedBpm)
        assertTrue(canDisplayEcg(points, result))
    }
    @Test fun weakSignalDoesNotProducePatientFlatlineDiagnosis() {
        val points = List(250) { 512f }
        val result = points.analyzeEcg()
        assertEquals(EcgStatus.FLATLINE, result.status)
        assertEquals(VitalLevel.WARNING, result.level)
        assertNull(result.estimatedBpm)
        assertTrue(canDisplayEcg(points, result))
    }
    @Test fun railClippingIsUnreliableNotHeartbeat() {
        val points = List(250) { if (it % 2 == 0) 0f else 1023f }
        val result = points.analyzeEcg()
        assertEquals(EcgStatus.NOISY, result.status)
        assertNull(result.estimatedBpm)
        assertFalse(canDisplayEcg(points, result))
        assertFalse(canDisplayEcg(points, EcgAnalysis(EcgStatus.NORMAL, "stale healthy result")))
    }
    @Test fun risingVoltageCanBeInspectedButIsNotCalledHeartbeat() {
        val points = List(250) { 300f + it }
        val result = points.analyzeEcg()
        assertNull(result.estimatedBpm)
        assertTrue(canDisplayEcg(points, result))
    }
    @Test fun usableTestSignalStillDisplays() {
        val points = pulses(250)
        val result = points.analyzeEcg()
        assertEquals(60, result.estimatedBpm)
        assertTrue(canDisplayEcg(points, result))
    }
    @Test fun invalidSamplesCannotBecomeBeats() {
        val points = pulses(249) + Float.NaN
        assertEquals(EcgStatus.NOISY, points.analyzeEcg().status)
        assertFalse(canDisplayEcg(points, EcgAnalysis(EcgStatus.NORMAL, "old result")))
    }
    @Test fun smallAmplitudeRawSignalDoesNotRequireBeatDetectorApproval() {
        val points = pulses(250).map { 512f + (it - 512f) / 20f }
        val result = points.analyzeEcg()
        assertNull(result.estimatedBpm)
        assertTrue(canDisplayEcg(points, result))
    }
    @Test fun priorQualityWarningDoesNotHideNewRawAcquisition() {
        assertTrue(canDisplayEcg(pulses(50), EcgAnalysis(EcgStatus.NOISY, "Previous clip")))
    }
}
