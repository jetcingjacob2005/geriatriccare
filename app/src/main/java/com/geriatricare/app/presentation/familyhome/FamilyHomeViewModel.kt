package com.geriatricare.app.presentation.familyhome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.repository.AuthRepository
import com.geriatricare.app.data.repository.PatientRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FamilyHomeViewModel @Inject constructor(
    private val patientRepo: PatientRepository,
    private val authRepo   : AuthRepository
) : ViewModel() {

    val linkedPatients: StateFlow<List<Patient>> = patientRepo
        .getPatientsByFamilyEmail(authRepo.currentUser?.email.orEmpty())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun logout() = authRepo.logout()
}
