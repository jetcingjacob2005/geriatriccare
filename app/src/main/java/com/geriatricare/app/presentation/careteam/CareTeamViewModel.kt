package com.geriatricare.app.presentation.careteam

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.model.CareRequest
import com.geriatricare.app.data.model.CareRequestStatus
import com.geriatricare.app.data.model.CareRequestType
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.repository.CareRequestRepository
import com.geriatricare.app.data.repository.PatientRepository
import com.geriatricare.app.data.model.Appointment
import com.geriatricare.app.data.model.AppointmentStatus
import com.geriatricare.app.data.repository.AppointmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CareTeamUiState(
    val patient  : Patient? = null,
    val isSending: Boolean  = false
)

@HiltViewModel
class CareTeamViewModel @Inject constructor(
    private val patientRepo     : PatientRepository,
    private val careRequestRepo : CareRequestRepository,
    private val appointmentRepo : AppointmentRepository,
    savedStateHandle            : SavedStateHandle
) : ViewModel() {

    val patientId: String = savedStateHandle["patientId"] ?: ""

    private val _uiState = MutableStateFlow(CareTeamUiState())
    val uiState: StateFlow<CareTeamUiState> = _uiState

    val requests: StateFlow<List<CareRequest>> = careRequestRepo.getRequests(patientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val p = patientRepo.getPatient(patientId)
            _uiState.update { it.copy(patient = p) }
        }
    }

    private val _lastBookedAppointment = MutableStateFlow<CareRequest?>(null)
    val lastBookedAppointment: StateFlow<CareRequest?> = _lastBookedAppointment

    fun dismissBookingConfirmation() {
        _lastBookedAppointment.value = null
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            careRequestRepo.send(
                CareRequest(patientId = patientId, type = CareRequestType.MESSAGE, message = text.trim())
            )
            _uiState.update { it.copy(isSending = false) }
        }
    }

    fun requestAppointment(
        preferredDate: String,
        reason: String,
        doctorName: String = "",
        hospitalName: String = "",
        specialty: String = "",
        doctorPhone: String = "",
        timeSlot: String = ""
    ) {
        if (preferredDate.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            val bookingCode = "#GC-${(1000..9999).random()}"
            val request = CareRequest(
                patientId     = patientId,
                type          = CareRequestType.APPOINTMENT,
                message       = reason.trim(),
                requestedDate = preferredDate.trim(),
                doctorName    = doctorName.trim(),
                hospitalName  = hospitalName.trim(),
                specialty     = specialty.trim(),
                doctorPhone   = doctorPhone.trim(),
                timeSlot      = timeSlot.trim(),
                bookingCode   = bookingCode,
                status        = CareRequestStatus.PENDING // Awaiting doctor confirmation
            )
            careRequestRepo.send(request)
            
            // Also synchronize with the Doctor Appointment Ecosystem
            appointmentRepo.create(
                Appointment(
                    patientId = patientId,
                    patientName = _uiState.value.patient?.name.orEmpty(),
                    doctorId = _uiState.value.patient?.doctorId.orEmpty(),
                    doctorName = doctorName.ifBlank { _uiState.value.patient?.doctorName ?: "Dr. Jetcin" },
                    hospitalName = hospitalName.ifBlank { _uiState.value.patient?.hospitalName ?: "Chill Cheyyu Hospital" },
                    date = preferredDate.trim(),
                    timeSlot = timeSlot.trim(),
                    reason = reason.trim(),
                    status = AppointmentStatus.PENDING,
                    bookingCode = bookingCode
                )
            )

            _lastBookedAppointment.value = request
            _uiState.update { it.copy(isSending = false) }
        }
    }

    fun updateStatus(requestId: String, status: CareRequestStatus) {
        viewModelScope.launch {
            careRequestRepo.updateStatus(patientId, requestId, status)
        }
    }
}