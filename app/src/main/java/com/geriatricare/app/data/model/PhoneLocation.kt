package com.geriatricare.app.data.model

/** Latest actual fix from the phone carried by the patient, not from the ESP32. */
data class PhoneLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val observedAt: Long = 0L,
    val sharingActive: Boolean = false,
    val locationAvailable: Boolean = false
) {
    fun hasValidFix(): Boolean = observedAt > 0L && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun isLive(now: Long): Boolean = hasValidFix() && sharingActive && locationAvailable &&
        now - observedAt in 0..120_000L
}
