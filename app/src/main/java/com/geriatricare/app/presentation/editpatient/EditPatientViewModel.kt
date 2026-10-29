package com.geriatricare.app.presentation.editpatient

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.repository.PatientRepository
import com.geriatricare.app.presentation.addpatient.isValidPhone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditPatientUiState(
    val patient  : Patient? = null,
    val isLoading: Boolean  = true,
    val isSaving : Boolean  = false,
    val isSaved  : Boolean  = false,
    val error    : String   = ""
)

@HiltViewModel
class EditPatientViewModel @Inject constructor(
    private val patientRepo: PatientRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val patientId: String = savedStateHandle["patientId"] ?: ""

    private val _state = MutableStateFlow(EditPatientUiState())
    val state: StateFlow<EditPatientUiState> = _state

    init {
        viewModelScope.launch {
            val p = patientRepo.getPatient(patientId)
            _state.update { it.copy(patient = p, isLoading = false) }
        }
    }

    fun savePatient(
        name: String, age: String, gender: String,
        condition: String, room: String, bloodGroup: String,
        emergencyContact: String, emergencyPhone: String,
        doctorName: String, doctorPhone: String,
        doctorEmail: String, hospitalName: String,
        familyEmails: String = ""
    ) {
        val existing = _state.value.patient ?: return
        if (name.isBlank()) { _state.update { it.copy(error = "Patient name is required.") }; return }
        if (emergencyPhone.isNotBlank() && !isValidPhone(emergencyPhone)) {
            _state.update { it.copy(error = "Enter a valid bystander phone number (10 digits, optionally with country code).") }
            return
        }
        if (doctorPhone.isNotBlank() && !isValidPhone(doctorPhone)) {
            _state.update { it.copy(error = "Enter a valid doctor phone number (10 digits, optionally with country code).") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = "") }
            val updated = existing.copy(
                name             = name.trim(),
                age              = age.toIntOrNull() ?: 0,
                gender           = gender,
                condition        = condition.trim(),
                roomNumber       = room.trim(),
                bloodGroup       = bloodGroup.trim(),
                emergencyContact = emergencyContact.trim(),
                emergencyPhone   = emergencyPhone.trim(),
                doctorName       = doctorName.trim(),
                doctorPhone      = doctorPhone.trim(),
                doctorEmail      = doctorEmail.trim(),
                hospitalName     = hospitalName.trim(),
                familyEmails     = familyEmails.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
            )
            patientRepo.updatePatient(updated)
                .onSuccess { _state.update { it.copy(isSaving = false, isSaved = true, patient = updated) } }
                .onFailure { e -> _state.update { it.copy(isSaving = false, error = e.message ?: "Save failed") } }
        }
    }
}
