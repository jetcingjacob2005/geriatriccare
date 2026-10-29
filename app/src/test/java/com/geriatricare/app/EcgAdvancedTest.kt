package com.geriatricare.app

import com.geriatricare.app.data.bluetooth.EcgStreamBuffer
import com.geriatricare.app.data.model.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class EcgAdvancedTest {
    @Test fun vitalFlowEqualityEmitsContactLossButNotEveryWaveSample() {
        assertNotEquals(BleData(ecg=512),BleData(ecg=-1))
        assertEquals(BleData(ecg=512),BleData(ecg=600))
        assertEquals(BleData(ecg=512).hashCode(),BleData(ecg=600).hashCode())
        assertEquals(-1,BleData().ecg)
    }
    @Test fun snapshotContainsActualSamplesAndRejectsContactGaps() {
        val snapshot = JsonParser.parseString(EcgSnapshot.json(listOf(500f,520f),250,123L)).asJsonObject
        assertEquals(520f,snapshot["samples"].asJsonArray[1].asFloat,0f)
        assertEquals(250,snapshot["sampleRateHz"].asInt)
        assertTrue(runCatching { EcgSnapshot.json(listOf(500f,-1f),250,123L) }.isFailure)
        assertTrue(runCatching { EcgSnapshot.json(listOf(500f,Float.NaN),250,123L) }.isFailure)
    }
    // Mathematical fixtures only: no synthetic patient data or accuracy claim.
    private fun fixture(fs: Int = 250, inverted: Boolean = false): List<Float> = List(fs * 8) { i ->
        val t = i.toDouble() / fs % 1.0
        fun gaussian(c: Double, w: Double, a: Double) = a * exp(-((t-c)/w)*((t-c)/w)/2)
        val wave = gaussian(.4, .016, 180.0) + gaussian(.23, .023, 30.0) + gaussian(.65, .048, 55.0)
        (512 + wave * if (inverted) -1 else 1).toFloat()
    }
    private fun packet(seq: Long = 0, time: Long = 0, timing: Boolean = true, value: Int = 512) =
        JsonParser.parseString("""{"es":[${List(10) { value }.joinToString(",")}],"fs":250,"seq":$seq,"t":$time,"timing":$timing}""").asJsonObject

    @Test fun contiguousBatchesRetainSampleOrderAndRate() {
        val stream = EcgStreamBuffer()
        stream.accept(packet()); stream.accept(packet(10, 40))
        assertEquals(250, stream.sampleRateHz); assertEquals(20, stream.analysis.size)
    }
    @Test fun droppedBatchStartsNewWindowInsteadOfInventingTiming() {
        val stream = EcgStreamBuffer(); stream.accept(packet())
        stream.accept(packet(20, 80, value = 600))
        assertEquals(10, stream.analysis.size); assertEquals(600f, stream.analysis.first(), 0f)
    }
    @Test fun uncertainAcquisitionTimingAndMalformedValuesClearHistory() {
        val stream = EcgStreamBuffer(); stream.accept(packet())
        stream.accept(packet(10, 40, false)); assertTrue(stream.analysis.isEmpty())
        stream.accept(packet()); stream.accept(packet(10, 40, value = 9000))
        assertTrue(stream.analysis.isEmpty())
    }
    @Test fun leadOffInsideBatchDoesNotBridgeBeats() {
        val stream = EcgStreamBuffer(); stream.accept(packet())
        stream.accept(JsonParser.parseString("""{"es":[512,-1,520,530],"fs":250,"seq":10,"t":40,"timing":true}""").asJsonObject)
        assertTrue(stream.analysis.isEmpty())
        assertTrue(stream.display.isEmpty())
    }
    @Test fun timestampWrapIsAcceptedWithoutFalseGap() {
        val stream = EcgStreamBuffer(); stream.accept(packet(0, 0xfffffff0L))
        stream.accept(packet(10, 24)); assertEquals(20, stream.analysis.size)
    }
    @Test fun boundedC3SchedulingJitterKeepsTheContiguousWaveform() {
        val stream = EcgStreamBuffer(); stream.accept(packet(0, 0))
        stream.accept(packet(10, 52)); assertEquals(20, stream.analysis.size)
        stream.accept(packet(20, 125)); assertEquals(10, stream.analysis.size)
    }
    @Test fun legacySwitchClearsHighRateHistory() {
        val stream = EcgStreamBuffer(); stream.accept(packet()); stream.legacy(550f)
        assertEquals(50, stream.sampleRateHz); assertEquals(listOf(550f), stream.analysis)
    }
    @Test fun buffersAreBoundedAndChartKeepsSixSecondScale() {
        val stream = EcgStreamBuffer()
        repeat(250) { stream.accept(packet(it*10L, it*40L)) }
        assertEquals(2000, stream.analysis.size); assertEquals(1500, stream.display.size)
        assertEquals(999f, EcgDisplayWindow.x(999, 1000, 999f, 1000), 0f)
    }
    @Test fun clippedFlatAndNoContactCannotProduceIntervals() {
        for (points in listOf(List(2000) { 512f }, List(2000) { 1023f }, fixture() + -1f)) {
            val result = EcgMeasurementEngine.measure(points,250)
            assertNull(result.heartRateBpm); assertNull(result.qrsMs); assertNull(result.qtMs)
        }
    }
    @Test fun lowSamplingRateNeverClaimsFineIntervals() {
        val result = EcgMeasurementEngine.measure(fixture(50),50)
        assertNull(result.prMs); assertNull(result.qrsMs); assertNull(result.qtMs)
    }
    @Test fun clearFixtureHasReasonableBeatTimingAndBoundaries() {
        val result = EcgMeasurementEngine.measure(fixture(),250)
        assertEquals(60.0, result.heartRateBpm!!, 1.0)
        assertEquals(1000.0, result.rrMs!!, 10.0)
        assertNotNull(result.qrsMs)
        assertTrue(result.qrsMs!! in 24.0..200.0)
        assertNotNull("PR on a clear fixture: $result", result.prMs)
        assertNotNull("P-wave on a clear fixture: $result", result.pWaveMs)
        assertNotNull("QT on a clear fixture: $result", result.qtMs)
        assertNotNull("QTc on a clear fixture: $result", result.qtcFridericiaMs)
    }
    @Test fun invertedFixtureStillHasBeatTiming() {
        assertEquals(60.0, EcgMeasurementEngine.measure(fixture(inverted=true),250).heartRateBpm!!, 1.0)
    }
    @Test fun highRateStreamPreservesLegacyScreeningFeatureContract() {
        val raw = fixture()
        val result = raw.analyzeEcg(250f)
        val legacy = raw.filterIndexed { i,_ -> i % 5 == 0 }.takeLast(250).analyzeEcg()
        assertEquals(legacy.estimatedBpm,result.estimatedBpm)
        assertEquals(legacy.rrVariabilityRatio,result.rrVariabilityRatio)
        assertNotNull(result.measurements)
    }
}
