package com.geriatricare.app

import com.geriatricare.app.data.bluetooth.SensorPacketDecoder
import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.ml.ScreeningReadiness
import org.junit.Assert.*
import org.junit.Test

class SensorScreeningTest {
    private val previous = BleData(ecg = 512, hr = 180f, spo2 = 98f, temp = 40f)
    @Test fun opticalContactLossOverridesPlausibleOrMissingReadings() {
        for (packet in listOf("""{"finger":false,"hr":72,"spo2":98,"ecg":512}""", """{"finger":false}""")) {
            val result = SensorPacketDecoder.decode(packet, previous)
            assertEquals(0f, result.hr, 0f)
            assertEquals(0f, result.spo2, 0f)
            assertEquals(512, result.ecg) // Independent ECG sensor is not disabled.
        }
    }
    @Test fun ecgLeadOffOverridesPlausibleWaveWithoutDisablingOpticalSensor() {
        val result = SensorPacketDecoder.decode("""{"leadOff":true,"ecg":512,"finger":true,"hr":72,"spo2":98}""", previous)
        assertEquals(-1, result.ecg)
        assertEquals(72f, result.hr, 0f)
        assertEquals(98f, result.spo2, 0f)
    }
    @Test fun malformedContactFlagsFailClosed() {
        val result = SensorPacketDecoder.decode("""{"finger":null,"leadOff":null}""", previous)
        assertEquals(0f, result.hr, 0f)
        assertEquals(0f, result.spo2, 0f)
        assertEquals(-1, result.ecg)
    }
    @Test fun sensorLossDoesNotKeepOldHighReadings() {
        val result = SensorPacketDecoder.decode("""{"hr":0,"spo2":0,"temp":85,"ecg":-1}""", previous)
        assertEquals(0f, result.hr, 0f)
        assertEquals(0f, result.spo2, 0f)
        assertEquals(0f, result.temp, 0f)
        assertEquals(-1, result.ecg)
    }
    @Test fun actualHighButPlausibleReadingsAreNotArtificiallyLowered() {
        val result = SensorPacketDecoder.decode("""{"hr":180,"spo2":88,"temp":40}""", BleData())
        assertEquals(180f, result.hr, 0f)
        assertEquals(88f, result.spo2, 0f)
        assertEquals(40f, result.temp, 0f)
    }
    @Test fun partialPacketPreservesUnspecifiedVitals() {
        val result = SensorPacketDecoder.decode("""{"ecg":550}""", previous)
        assertEquals(previous.hr, result.hr, 0f)
        assertEquals(550, result.ecg)
    }
    @Test fun corruptAndNullValuesClearOldVitals() {
        val result = SensorPacketDecoder.decode("""{"hr":"NaN","spo2":101,"temp":null}""", previous)
        assertEquals(listOf("heart rate", "SpO₂", "temperature"), ScreeningReadiness.missingVitals(result))
    }
    @Test fun firstCompleteFeatureSetIsImmediatelyReady() {
        assertNull(ScreeningReadiness.status(1, 72, 0.1, "Steady"))
        assertTrue(ScreeningReadiness.resultStatus(1).contains("Immediate provisional estimate"))
    }
    @Test fun fullWindowWithNoDetectedBeatsExplainsBlocker() {
        assertTrue(ScreeningReadiness.status(1500, null, null, "Weak or no signal")!!.contains("Weak or no signal"))
    }
    @Test fun fullWindowAndUsableEcgReady() {
        assertNull(ScreeningReadiness.status(1500, 72, 0.1, "Steady"))
        assertNotNull(ScreeningReadiness.status(1500, 900, 0.1, "Noisy"))
        assertTrue(ScreeningReadiness.resultStatus(1500).contains("30-minute"))
    }

    @Test fun noSensorSampleStillBlocksPrediction() {
        assertTrue(ScreeningReadiness.status(0, 72, 0.1, "Steady")!!.contains("complete live sensor"))
    }
}
