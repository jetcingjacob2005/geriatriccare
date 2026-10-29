package com.geriatricare.app.presentation.doctordashboard

import com.geriatricare.app.data.model.Appointment
import com.geriatricare.app.data.model.AppointmentStatus
import com.geriatricare.app.data.model.Patient

/** Patients with an active appointment for the signed-in doctor only. */
object DoctorPatientSelection {
    fun bookedPatients(
        doctorId: String,
        allPatients: List<Patient>,
        appointments: List<Appointment>
    ): List<Patient> {
        if (doctorId.isBlank()) return emptyList()
        val relevant = appointments.filter {
            it.doctorId == doctorId && it.patientId.isNotBlank() &&
                it.status != AppointmentStatus.CANCELLED && it.status != AppointmentStatus.REJECTED
        }
        val bookedIds = relevant.map { it.patientId }.toSet()
        val existing = allPatients.filter { it.id in bookedIds }
        val existingIds = existing.map { it.id }.toSet()
        val synthesized = relevant.filter { it.patientId !in existingIds }
            .distinctBy { it.patientId }
            .map { appointment ->
                Patient(
                    id = appointment.patientId,
                    name = appointment.patientName.ifBlank { "Patient (${appointment.patientId.take(4)})" },
                    doctorName = appointment.doctorName,
                    hospitalName = appointment.hospitalName
                )
            }
        return existing + synthesized
    }
}
