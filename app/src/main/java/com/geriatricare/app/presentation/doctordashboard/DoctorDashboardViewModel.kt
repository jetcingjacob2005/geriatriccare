package com.geriatricare.app.presentation.doctordashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.model.*
import com.geriatricare.app.data.repository.*
import com.geriatricare.app.data.auth.UserRoleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class DoctorDashboardViewModel @Inject constructor(
    private val appointmentRepo: AppointmentRepository,
    private val slotRepo: AppointmentSlotRepository,
    private val prescriptionRepo: PrescriptionRepository,
    private val medicationReminderRepo: MedicationReminderRepository,
    private val patientRepo: PatientRepository,
    private val authRepo: AuthRepository,
    private val userRoleManager: UserRoleManager
) : ViewModel() {

    private val doctorId: String get() = authRepo.currentUid ?: ""
    private val todayDate: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val patients: StateFlow<List<Patient>> =
        patientRepo.getAllHospitalPatients()
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appointments: StateFlow<List<Appointment>> =
        appointmentRepo.getAllAppointments()
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filter to display ONLY patients who have booked an appointment with this doctor
    val bookedPatients: StateFlow<List<Patient>> =
        combine(patients, appointments) { allPatients, appts ->
            DoctorPatientSelection.bookedPatients(doctorId, allPatients, appts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val prescriptions: StateFlow<List<Prescription>> =
        prescriptionRepo.getForDoctor(doctorId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todaySlots: StateFlow<List<AppointmentSlot>> =
        slotRepo.getSlotsForDoctor(doctorId, todayDate)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun confirmAppointment(appointmentId: String) {
        viewModelScope.launch {
            appointmentRepo.updateStatus(appointmentId, AppointmentStatus.CONFIRMED)
        }
    }

    fun rejectAppointment(appointmentId: String) {
        viewModelScope.launch {
            appointmentRepo.updateStatus(appointmentId, AppointmentStatus.REJECTED)
        }
    }

    fun completeAppointment(appointmentId: String) {
        viewModelScope.launch {
            appointmentRepo.updateStatus(appointmentId, AppointmentStatus.COMPLETED)
        }
    }

    fun prescribeMedication(
        patientId: String,
        patientName: String,
        medicationName: String,
        dosage: String,
        frequency: String,
        startDate: String,
        endDate: String,
        instructions: String,
        reminderTime: String,
        appointmentId: String? = null
    ) {
        viewModelScope.launch {
            val presId = UUID.randomUUID().toString()
            val prescription = Prescription(
                id = presId,
                patientId = patientId,
                doctorId = doctorId,
                doctorName = "Dr. Jetcin",
                patientName = patientName,
                medicationName = medicationName,
                dosage = dosage,
                frequency = frequency,
                startDate = startDate,
                endDate = endDate,
                instructions = instructions,
                reminderTime = reminderTime
            )
            prescriptionRepo.prescribe(prescription)

            // Persist a reminder for every date in the prescribed interval.
            // The patient device turns these into local Android notifications;
            // it does not need a paid server to wake up for each dose.
            val doseTimes = reminderTime.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val reminderDates = datesInPrescriptionPeriod(startDate, endDate)
            reminderDates.forEach { date ->
                doseTimes.forEach { time ->
                    val hour = time.split(":").firstOrNull()?.toIntOrNull() ?: 8
                    val slotDetail = when {
                        hour < 11 -> {
                            if (instructions.contains("before breakfast", ignoreCase = true) || frequency.contains("before breakfast", ignoreCase = true))
                                "Morning • Before Breakfast (Before Food)"
                            else
                                "Morning • After Breakfast (After Food)"
                        }
                        hour in 11..15 -> {
                            if (instructions.contains("before lunch", ignoreCase = true) || frequency.contains("before lunch", ignoreCase = true))
                                "Afternoon • Before Lunch (Before Food)"
                            else
                                "Afternoon • After Lunch (After Food)"
                        }
                        hour in 16..20 -> {
                            if (instructions.contains("before dinner", ignoreCase = true) || frequency.contains("before dinner", ignoreCase = true))
                                "Night • Before Dinner (Before Food)"
                            else
                                "Night • After Dinner (After Food)"
                        }
                        else -> "Bedtime • Before Sleep"
                    }

                    medicationReminderRepo.create(
                        MedicationReminder(
                            prescriptionId = prescription.id,
                            patientId = patientId,
                            medicationName = medicationName,
                            dosage = dosage,
                            scheduledTime = time,
                            date = date,
                            mealTiming = slotDetail,
                            instructions = instructions
                        )
                    )
                }
            }

            // If prescribed during an active appointment consultation, mark consultation completed
            if (!appointmentId.isNullOrBlank()) {
                appointmentRepo.updateStatus(
                    appointmentId,
                    AppointmentStatus.COMPLETED,
                    notes = "Consultation completed. Prescribed $medicationName ($dosage)."
                )
            }
        }
    }

    fun deletePrescription(prescriptionId: String) {
        viewModelScope.launch {
            prescriptionRepo.delete(prescriptionId)
            medicationReminderRepo.deleteForPrescription(prescriptionId)
        }
    }

    /** Returns valid future prescription dates, inclusively, capped at one year. */
    private fun datesInPrescriptionPeriod(start: String, end: String): List<String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val today = formatter.parse(todayDate) ?: return listOf(todayDate)
        val startDate = runCatching { formatter.parse(start) }.getOrNull() ?: today
        val endDate = runCatching { formatter.parse(end) }.getOrNull() ?: startDate
        if (endDate.before(today) || endDate.before(startDate)) return emptyList()

        val calendar = Calendar.getInstance().apply { time = if (startDate.before(today)) today else startDate }
        val results = mutableListOf<String>()
        while (!calendar.time.after(endDate) && results.size < MAX_REMINDER_DAYS) {
            results += formatter.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return results
    }

    private companion object {
        const val MAX_REMINDER_DAYS = 366
    }

    fun createDefaultSlots(date: String) {
        viewModelScope.launch {
            slotRepo.createDefaultSlots(doctorId, date)
        }
    }

    fun blockSlot(slotId: String) {
        viewModelScope.launch {
            slotRepo.updateSlotStatus(slotId, SlotStatus.BLOCKED)
        }
    }

    fun releaseSlot(slotId: String) {
        viewModelScope.launch {
            slotRepo.updateSlotStatus(slotId, SlotStatus.AVAILABLE)
        }
    }

    fun logout() {
        userRoleManager.clear()
        authRepo.logout()
    }
}
