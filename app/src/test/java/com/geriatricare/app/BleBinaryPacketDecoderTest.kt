package com.geriatricare.app

import com.geriatricare.app.data.bluetooth.BleBinaryPacketDecoder
import com.geriatricare.app.data.bluetooth.EcgStreamBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBinaryPacketDecoderTest {
    @Test fun decodesVitalsExactly() {
        val bytes = byteArrayOf(
            0xD1.toByte(), 0x03, 0xD5.toByte(), 0x02, 0xD4.toByte(), 0x03,
            0x6E, 0x01, 0x78, 0x56, 0x34, 0x12
        )
        val packet = BleBinaryPacketDecoder.decode(bytes) as BleBinaryPacketDecoder.Vitals
        assertEquals(72.5f, packet.heartRate)
        assertEquals(98.0f, packet.spo2)
        assertEquals(36.6f, packet.temperature)
        assertTrue(packet.fingerPresent)
        assertTrue(packet.max30102Ready)
        assertEquals(0x12345678L, packet.latestIr)
    }

    @Test fun compactEcgAppearsImmediatelyAndRejectsSequenceGapFromAnalysis() {
        fun frame(sequence: Int, timing: Boolean = true): ByteArray {
            val result = ByteArray(20)
            result[0] = 0xE1.toByte(); result[1] = if (timing) 1 else 0
            result[2] = sequence.toByte(); result[3] = (sequence shr 8).toByte()
            repeat(8) { index ->
                val value = 500 + index
                result[4 + index * 2] = value.toByte()
                result[5 + index * 2] = (value shr 8).toByte()
            }
            return result
        }
        val stream = EcgStreamBuffer()
        val first = BleBinaryPacketDecoder.decode(frame(0)) as BleBinaryPacketDecoder.Ecg
        stream.acceptBinary(first.samples, first.sequence, first.timingValid)
        assertEquals(8, stream.display.size)
        assertEquals(8, stream.analysis.size)

        val gap = BleBinaryPacketDecoder.decode(frame(24)) as BleBinaryPacketDecoder.Ecg
        stream.acceptBinary(gap.samples, gap.sequence, gap.timingValid)
        assertEquals(8, stream.display.size)
        assertTrue(stream.analysis.isEmpty())
    }

    @Test fun rejectsMalformedPacketLength() {
        assertNull(BleBinaryPacketDecoder.decode(ByteArray(19) { 0 }))
    }
}
