package com.geriatricare.app

import com.geriatricare.app.data.model.Appointment
import com.geriatricare.app.data.model.AppointmentStatus
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.presentation.doctordashboard.DoctorPatientSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class DoctorPatientSelectionTest {
    @Test fun showsOnlyThisDoctorsActivePatients() {
        val patients = listOf(Patient(id = "p1", name = "Alice"), Patient(id = "p2", name = "Bob"))
        val appointments = listOf(
            Appointment(patientId = "p1", doctorId = "doctor-a"),
            Appointment(patientId = "p2", doctorId = "doctor-b"),
            Appointment(patientId = "p3", patientName = "Carol", doctorId = "doctor-a"),
            Appointment(patientId = "p4", doctorId = "doctor-a", status = AppointmentStatus.CANCELLED)
        )
        assertEquals(listOf("p1", "p3"),
            DoctorPatientSelection.bookedPatients("doctor-a", patients, appointments).map { it.id })
    }

    @Test fun signedOutDoctorSeesNoPatients() {
        assertEquals(emptyList<Patient>(), DoctorPatientSelection.bookedPatients(
            "", listOf(Patient(id = "p1")), listOf(Appointment(patientId = "p1", doctorId = ""))))
    }
}
