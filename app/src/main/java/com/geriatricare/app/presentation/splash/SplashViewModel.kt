package com.geriatricare.app.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.auth.UserRoleManager
import com.geriatricare.app.data.repository.AuthRepository
import com.geriatricare.app.data.repository.PatientRepository
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class SplashDestination { LOGIN, CAREGIVER, FAMILY, DOCTOR }

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val patientRepo   : PatientRepository,
    private val userRoleManager: UserRoleManager,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _destination = MutableStateFlow<SplashDestination?>(null)
    val destination: StateFlow<SplashDestination?> = _destination

    init {
        viewModelScope.launch {
            if (!authRepository.isLoggedIn) {
                _destination.value = SplashDestination.LOGIN
                return@launch
            }

            // 1. Check local saved role
            var role = userRoleManager.getRole()

            // 2. If not stored locally, check Firestore
            if (role.isNullOrBlank()) {
                val uid = authRepository.currentUid.orEmpty()
                if (uid.isNotEmpty()) {
                    try {
                        val doc = firestore.collection("users").document(uid).get().await()
                        role = doc.getString("role")
                        if (!role.isNullOrBlank()) {
                            userRoleManager.saveRole(role)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. Resolve destination
            _destination.value = when (role) {
                UserRoleManager.ROLE_DOCTOR -> SplashDestination.DOCTOR
                UserRoleManager.ROLE_FAMILY -> SplashDestination.FAMILY
                UserRoleManager.ROLE_CAREGIVER -> SplashDestination.CAREGIVER
                else -> {
                    val ownPatients = patientRepo.getPatientsOnce()
                    if (ownPatients.isNotEmpty()) {
                        SplashDestination.CAREGIVER
                    } else {
                        val familyPatients = patientRepo.getPatientsByFamilyEmailOnce(authRepository.currentUser?.email.orEmpty())
                        if (familyPatients.isNotEmpty()) SplashDestination.FAMILY else SplashDestination.CAREGIVER
                    }
                }
            }
        }
    }
}
