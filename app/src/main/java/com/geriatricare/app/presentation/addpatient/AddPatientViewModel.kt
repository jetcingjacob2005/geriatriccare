package com.geriatricare.app.presentation.addpatient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.repository.PatientRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddPatientUiState(
    val isLoading: Boolean = false,
    val isSaved  : Boolean = false,
    val error    : String  = ""
)

@HiltViewModel
class AddPatientViewModel @Inject constructor(
    private val patientRepo: PatientRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddPatientUiState())
    val state: StateFlow<AddPatientUiState> = _state

    fun savePatient(
        name: String, age: String, gender: String,
        condition: String, room: String, bloodGroup: String,
        emergencyContact: String, emergencyPhone: String,
        doctorName: String = "", doctorPhone: String = "",
        doctorEmail: String = "", hospitalName: String = "",
        familyEmails: String = ""
    ) {
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
            _state.update { it.copy(isLoading = true, error = "") }
            val patient = Patient(
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
                familyEmails     = familyEmails.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() },
                admissionDate    = java.time.LocalDate.now().toString()
            )
            patientRepo.addPatient(patient)
                .onSuccess { _state.update { it.copy(isLoading = false, isSaved = true) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Save failed") } }
        }
    }
}

/** Accepts 10-digit local numbers or numbers with a country code (10-15 digits total). */
fun isValidPhone(raw: String): Boolean {
    val digits = raw.filter { it.isDigit() }
    return digits.length in 10..15
}
