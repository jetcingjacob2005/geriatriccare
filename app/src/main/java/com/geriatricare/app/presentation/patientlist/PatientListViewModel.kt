package com.geriatricare.app.presentation.patientlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.auth.UserRoleManager
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.repository.AuthRepository
import com.geriatricare.app.data.repository.PatientRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PatientListUiState(
    val patients    : List<Patient> = emptyList(),
    val isLoading   : Boolean = true,
    val alertCount  : Int = 0,
    val errorMessage: String = ""
)

@HiltViewModel
class PatientListViewModel @Inject constructor(
    private val patientRepo: PatientRepository,
    private val authRepo   : AuthRepository,
    private val userRoleManager: UserRoleManager
) : ViewModel() {

    private val _state = MutableStateFlow(PatientListUiState())
    val state: StateFlow<PatientListUiState> = _state

    init { observePatients() }

    private fun observePatients() {
        viewModelScope.launch {
            patientRepo.getPatients()
                .catch { e -> _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "") } }
                .collect { list ->
                    _state.update { it.copy(patients = list, isLoading = false) }
                }
        }
    }

    fun logout() {
        userRoleManager.clear()
        authRepo.logout()
    }
}
