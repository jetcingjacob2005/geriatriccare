package com.geriatricare.app.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.auth.UserRoleManager
import com.geriatricare.app.data.notification.NotificationTokenRegistrar
import com.geriatricare.app.data.repository.AuthRepository
import com.geriatricare.app.data.repository.AuthResult
import com.geriatricare.app.data.repository.PatientRepository
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class LoginDestination { CAREGIVER, FAMILY, DOCTOR }

data class LoginUiState(
    val isLoading : Boolean = false,
    val isSuccess : Boolean = false,
    val destination: LoginDestination = LoginDestination.CAREGIVER,
    val error     : String  = "",
    val selectedRole: String = UserRoleManager.ROLE_CAREGIVER
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val patientRepo   : PatientRepository,
    private val userRoleManager: UserRoleManager,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun setRole(role: String) {
        _state.value = _state.value.copy(selectedRole = role, error = "")
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(error = "Please enter email and password.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = "")
            when (val r = authRepository.login(email, password)) {
                is AuthResult.Success -> resolveDestination(email, _state.value.selectedRole)
                is AuthResult.Error   -> _state.value = _state.value.copy(isLoading = false, error = r.message)
            }
        }
    }

    fun register(email: String, password: String) {
        if (email.isBlank() || password.length < 6) {
            _state.value = _state.value.copy(error = "Password must be at least 6 characters.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = "")
            when (val r = authRepository.register(email, password)) {
                is AuthResult.Success -> resolveDestination(email, _state.value.selectedRole)
                is AuthResult.Error   -> _state.value = _state.value.copy(isLoading = false, error = r.message)
            }
        }
    }

    private suspend fun resolveDestination(email: String, activeSelectedRole: String) {
        val uid = authRepository.currentUid.orEmpty()
        
        // 1. If user explicitly picked DOCTOR or had DOCTOR role saved
        var determinedRole = activeSelectedRole
        
        // Check if Firestore already has a stored role for this user
        if (uid.isNotEmpty()) {
            try {
                val userDoc = firestore.collection("users").document(uid).get().await()
                val existingRole = userDoc.getString("role")
                if (!existingRole.isNullOrBlank()) {
                    determinedRole = existingRole
                } else {
                    // Save new user profile with selected role
                    firestore.collection("users").document(uid).set(
                        mapOf(
                            "uid" to uid,
                            "email" to email,
                            "role" to activeSelectedRole,
                            "hospital" to "Chill Cheyyu Hospital",
                            "doctorName" to if (activeSelectedRole == UserRoleManager.ROLE_DOCTOR) "Dr. Jetcin" else ""
                        )
                    ).await()
                }
            } catch (_: Exception) {
                // Network failure or offline — fallback to active selection
            }
        }

        userRoleManager.saveRole(determinedRole)
        // Register after authentication so the trusted alert service can target
        // this exact caregiver/family device while the app is backgrounded.
        NotificationTokenRegistrar.registerCurrentDevice()

        val destination = when (determinedRole) {
            UserRoleManager.ROLE_DOCTOR -> LoginDestination.DOCTOR
            UserRoleManager.ROLE_FAMILY -> LoginDestination.FAMILY
            else -> {
                // If Caregiver, verify if user belongs to family or caregiver
                val ownPatients = patientRepo.getPatientsOnce()
                if (ownPatients.isEmpty()) {
                    val familyPatients = patientRepo.getPatientsByFamilyEmailOnce(email)
                    if (familyPatients.isNotEmpty()) LoginDestination.FAMILY else LoginDestination.CAREGIVER
                } else {
                    LoginDestination.CAREGIVER
                }
            }
        }

        _state.value = _state.value.copy(isLoading = false, isSuccess = true, destination = destination)
    }

    fun clearError() { _state.value = _state.value.copy(error = "") }
}
