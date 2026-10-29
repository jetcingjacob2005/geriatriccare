package com.geriatricare.app.data.model

data class Appointment(
    val id: String = "",
    val patientId: String = "",
    val patientName: String = "",
    val doctorId: String = "",
    val doctorName: String = "Dr. Jetcin",
    val hospitalName: String = "Chill Cheyyu Hospital",
    val date: String = "",
    val timeSlot: String = "",
    val reason: String = "",
    val status: AppointmentStatus = AppointmentStatus.PENDING,
    val bookingCode: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

enum class AppointmentStatus { PENDING, CONFIRMED, REJECTED, COMPLETED, CANCELLED }
