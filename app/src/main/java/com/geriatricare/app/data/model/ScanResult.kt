package com.geriatricare.app.data.model

/**
 * A cloud-synced summary of one completed scan — written to Firestore
 * (patients/{patientId}/scanResults) so it's viewable remotely by a
 * linked family member, unlike the full local Room vitals history which
 * only lives on the caregiver's phone.
 *
 * This is intentionally a lightweight summary, not the full raw ECG
 * waveform (no need to sync that much data for a remote read-only view).
 */
data class ScanResult(
    val id           : String = "",
    val patientId    : String = "",
    val timestamp    : Long   = System.currentTimeMillis(),
    val temp         : Float  = 0f,
    val spo2         : Float  = 0f,
    val hr           : Float  = 0f,
    val ecgStatus    : String = "",
    val ecgMessage   : String = "",
    val ecgBpm       : Int?   = null,
    val insightText  : String = "",
    // Retired hardware-GPS fields kept for Firestore compatibility with old records.
    val lat          : Double = 0.0,
    val lon          : Double = 0.0,
    val hasFix       : Boolean = false
)
