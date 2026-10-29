package com.geriatricare.app.data.model

enum class CareRequestType   { MESSAGE, APPOINTMENT }
enum class CareRequestStatus { PENDING, CONFIRMED, DECLINED, COMPLETED }

/**
 * A message or appointment request sent to a patient's doctor/hospital.
 * Stored per-patient in Firestore under patients/{patientId}/careRequests.
 *
 * Note: there is currently no separate doctor-facing app/portal in this
 * project, so status updates (Confirmed/Declined/Completed) are made
 * manually inside this app once the doctor's office responds by phone —
 * this gives you a real record/history, not an automatic two-way sync.
 */
data class CareRequest(
    val id            : String            = "",
    val patientId     : String            = "",
    val type          : CareRequestType   = CareRequestType.MESSAGE,
    val message       : String            = "",
    val requestedDate : String            = "", // only used for APPOINTMENT requests
    val doctorName    : String            = "", // optional — set when booked via the doctor picker
    val hospitalName  : String            = "", // optional — set when booked via the doctor picker
    val specialty     : String            = "", // medical specialty (Cardiology, Geriatrics, etc.)
    val doctorPhone   : String            = "", // clinic/doctor contact phone
    val timeSlot      : String            = "", // selected consultation time slot
    val bookingCode   : String            = "", // unique tracking reference e.g. #GC-8421
    val status        : CareRequestStatus = CareRequestStatus.PENDING,
    val createdAt     : Long              = System.currentTimeMillis()
)
