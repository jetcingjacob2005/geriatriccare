package com.geriatricare.app.data.model

data class Prescription(
    val id: String = "",
    val patientId: String = "",
    val doctorId: String = "",
    val doctorName: String = "",
    val patientName: String = "",
    val medicationName: String = "",
    val dosage: String = "",
    val frequency: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val instructions: String = "",
    val reminderTime: String = "",
    val isActive: Boolean = true,
    val prescribedAt: Long = System.currentTimeMillis()
)
