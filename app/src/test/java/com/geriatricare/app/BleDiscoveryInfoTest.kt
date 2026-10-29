package com.geriatricare.app

import com.geriatricare.app.data.bluetooth.BleDiscoveryInfo
import org.junit.Assert.*
import org.junit.Test

class BleDiscoveryInfoTest {
    @Test fun advertisementNameOverridesAndroidCachedName() {
        val result = BleDiscoveryInfo.merge(null, "GeriatriCare_ESP32C3", "Old name", false)
        assertEquals("GeriatriCare_ESP32C3", result.name)
        assertTrue(result.isGeriatriCare)
    }

    @Test fun laterScanResponseReplacesUnknownName() {
        val first = BleDiscoveryInfo.merge(null, null, null, true)
        val next = BleDiscoveryInfo.merge(first, "GeriatriCare_ESP32C3", null, false)
        assertEquals("GeriatriCare_ESP32C3", next.name)
        assertTrue(next.hasSensorService)
    }

    @Test fun namelessFollowupDoesNotEraseKnownNameOrService() {
        val first = BleDiscoveryInfo.merge(null, "GeriatriCare_ESP32", null, true)
        assertEquals(first, BleDiscoveryInfo.merge(first, " ", null, false))
    }

    @Test fun serviceRecognizesUnnamedBoardButOtherDevicesStayUnclassified() {
        assertTrue(BleDiscoveryInfo.merge(null, null, null, true).isGeriatriCare)
        assertFalse(BleDiscoveryInfo.merge(null, "Headphones", null, false).isGeriatriCare)
    }

    @Test fun cachedNameIsFallback() {
        assertEquals("Cached board", BleDiscoveryInfo.merge(null, null, "Cached board", false).name)
    }
}
