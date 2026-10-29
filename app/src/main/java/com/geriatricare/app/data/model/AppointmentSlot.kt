package com.geriatricare.app.data.model

data class AppointmentSlot(
    val id: String = "",
    val doctorId: String = "",
    val date: String = "",
    val timeSlot: String = "",
    val status: SlotStatus = SlotStatus.AVAILABLE
)

enum class SlotStatus { AVAILABLE, BOOKED, BLOCKED }
