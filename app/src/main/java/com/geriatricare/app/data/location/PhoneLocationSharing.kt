package com.geriatricare.app.data.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LocationSharingState(
    val patientId: String = "",
    val active: Boolean = false,
    val message: String = "Location sharing is off."
)

@Singleton
class PhoneLocationSharing @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutableState = MutableStateFlow(LocationSharingState())
    val state = mutableState.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called from the visible dashboard after Android location permission is granted. */
    fun start(patientId: String) {
        if (patientId.isBlank() || !hasPermission()) {
            update(LocationSharingState(patientId, false, "Allow location permission to share this phone's position."))
            return
        }
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, PatientLocationService::class.java).putExtra(PatientLocationService.PATIENT_ID, patientId))
        } catch (_: RuntimeException) {
            update(LocationSharingState(patientId, false, "Could not start location sharing. Open the dashboard and try again."))
        }
    }

    fun stop() {
        context.stopService(Intent(context, PatientLocationService::class.java))
    }

    internal fun update(value: LocationSharingState) { mutableState.value = value }
}
