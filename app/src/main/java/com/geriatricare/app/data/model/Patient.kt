package com.geriatricare.app.data.model

/**
 * Represents a geriatric patient stored in Firestore.
 * doctorId ties the patient to the logged-in caregiver.
 */
data class Patient(
    val id: String = "",
    val name: String = "",
    val age: Int = 0,
    val gender: String = "",        // "Male" | "Female" | "Other"
    val condition: String = "",     // Primary diagnosis
    val roomNumber: String = "",
    val bloodGroup: String = "",
    val emergencyContact: String = "",
    val emergencyPhone: String = "",
    val doctorId: String = "",      // Firebase UID of assigned caregiver
    val doctorName: String = "",
    val doctorPhone: String = "",
    val doctorEmail: String = "",
    val hospitalName: String = "",
    val familyEmails: List<String> = emptyList(), // children/family who can view read-only + message doctor
    val bleDeviceAddress: String = "", // MAC address of the ESP32 BLE device
    val photoUrl: String = "",
    val admissionDate: String = "",
    val latestCameraFrame: String = "",
    val fallStatus: String = "NORMAL",
    /** Epoch time from the station camera that last confirmed this active state.
     * Family clients must not treat an old persisted state as a live emergency. */
    val fallStatusObservedAt: Long = 0L,
    val phoneLocation: PhoneLocation? = null
)
