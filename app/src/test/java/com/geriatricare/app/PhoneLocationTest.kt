package com.geriatricare.app

import com.geriatricare.app.data.model.PhoneLocation
import org.junit.Assert.*
import org.junit.Test

class PhoneLocationTest {
    private val now = 1_000_000L
    private val fix = PhoneLocation(12.0, 77.0, 10f, now - 30_000, true, true)

    @Test fun freshAvailableFixIsLive() { assertTrue(fix.isLive(now)) }
    @Test fun locationOffKeepsCoordinateButIsNotLive() {
        val off = fix.copy(locationAvailable = false)
        assertTrue(off.hasValidFix())
        assertFalse(off.isLive(now))
    }
    @Test fun stoppedSharingIsNotLive() { assertFalse(fix.copy(sharingActive = false).isLive(now)) }
    @Test fun staleOrFutureFixIsNotLive() {
        assertFalse(fix.copy(observedAt = now - 120_001).isLive(now))
        assertFalse(fix.copy(observedAt = now + 1).isLive(now))
    }
    @Test fun invalidOrLegacyFixIsNotLive() {
        assertFalse(fix.copy(latitude = Double.NaN).isLive(now))
        assertFalse(PhoneLocation(12.0, 77.0, 10f, now, true).isLive(now))
    }
}
