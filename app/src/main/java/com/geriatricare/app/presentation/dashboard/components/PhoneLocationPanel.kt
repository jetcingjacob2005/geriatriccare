package com.geriatricare.app.presentation.dashboard.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.geriatricare.app.data.location.LocationSharingState
import com.geriatricare.app.data.model.PhoneLocation
import com.geriatricare.app.presentation.familydashboard.FamilyLocationMap
import com.geriatricare.app.ui.theme.Gray500
import com.geriatricare.app.ui.theme.White
import kotlinx.coroutines.delay

/** Explicitly associates this carried phone with one patient, rather than a family phone. */
@Composable
fun PhoneLocationPanel(
    patientId: String,
    location: PhoneLocation?,
    sharing: LocationSharingState,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit
) {
    val context = LocalContext.current
    var locationNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            locationNow = System.currentTimeMillis()
        }
    }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            permissionMessage = null
            onStartSharing()
        } else {
            permissionMessage = "Location permission is denied. Allow it in app settings to share with family."
        }
    }
    val activeForPatient = sharing.active && sharing.patientId == patientId

    Column(Modifier.fillMaxWidth()) {
        FamilyLocationMap(
            lat = location?.latitude ?: 0.0,
            lon = location?.longitude ?: 0.0,
            hasFix = location?.hasValidFix() == true,
            isLive = activeForPatient && location?.isLive(locationNow) == true,
            lastUpdatedLabel = location?.observedAt?.takeIf { it > 0L }?.let {
                java.text.SimpleDateFormat("dd MMM, hh:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date(it))
            } ?: "No position yet",
            statusLabel = if (activeForPatient) sharing.message else "Phone location sharing is off. Showing the last known position, if available.",
            accuracyMeters = location?.accuracyMeters,
            noFixMessage = "Start sharing below to use this phone's GPS and network location. Keep this phone with the patient."
        )
        Text("Share the phone carried by this patient with their linked family.",
            modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = Gray500)
        Text(permissionMessage ?: if (sharing.patientId == patientId) sharing.message else "Location sharing is off.",
            modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = White)
        Row {
            Button(onClick = {
                if (activeForPatient) onStopSharing()
                else {
                    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) onStartSharing()
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }) { Text(if (activeForPatient) "Stop sharing" else "Share phone location") }
            OutlinedButton(onClick = {
                val settings = if (permissionMessage != null) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                } else Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                context.startActivity(settings)
            }, modifier = Modifier.padding(start = 8.dp)) { Text("Settings") }
        }
    }
}
