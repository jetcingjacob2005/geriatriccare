package com.geriatricare.app.presentation.familydashboard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geriatricare.app.ui.theme.ClinicalBorder
import com.geriatricare.app.ui.theme.ClinicalCard
import com.geriatricare.app.ui.theme.ClinicalPaleBlue
import com.geriatricare.app.ui.theme.ClinicalText
import com.geriatricare.app.ui.theme.CyanAccent
import com.geriatricare.app.ui.theme.Gray500
import com.geriatricare.app.ui.theme.Navy900
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.geriatricare.app.presentation.dashboard.components.openLocationMap
import com.geriatricare.app.presentation.dashboard.components.isValidGpsCoordinate

/**
 * Shows the last valid coordinate shared by the phone carried by the patient.
 * Freshness is represented by the timestamp supplied by the caller.
 */
@Composable
fun FamilyLocationMap(
    lat: Double,
    lon: Double,
    hasFix: Boolean,
    lastUpdatedLabel: String,
    statusLabel: String? = null,
    accuracyMeters: Float? = null,
    noFixMessage: String = "No phone location has been shared yet.",
    isLive: Boolean = false
) {
    val context = LocalContext.current
    if (!hasFix || !isValidGpsCoordinate(lat, lon)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = ClinicalCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Patient location", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(noFixMessage, style = MaterialTheme.typography.bodySmall, color = Gray500)
            }
        }
        return
    }

    val mapView = remember { MapView(context).also { it.onCreate(null) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val patientLocation = remember(lat, lon) { LatLng(lat, lon) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = ClinicalCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) {
                Text("Patient location", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Text(if (isLive) "LIVE • recent position" else "NOT LIVE • last known position",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isLive) CyanAccent else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold)
                Text("GPS update: $lastUpdatedLabel", style = MaterialTheme.typography.labelSmall, color = Gray500)
                statusLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Gray500) }
                accuracyMeters?.takeIf { it.isFinite() && it > 0 }?.let {
                    Text("Estimated accuracy: ±${it.toInt()} m", style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
            }
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                update = { view ->
                    view.getMapAsync { map: GoogleMap ->
                        map.uiSettings.isMapToolbarEnabled = false
                        map.uiSettings.isZoomControlsEnabled = false
                        map.clear()
                        map.addMarker(MarkerOptions().position(patientLocation)
                            .title(if (isLive) "Patient recent location" else "Last known position — not live")
                            .icon(BitmapDescriptorFactory.defaultMarker(if (isLive)
                                BitmapDescriptorFactory.HUE_CYAN else BitmapDescriptorFactory.HUE_ORANGE)))
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(patientLocation, 16f))
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        openLocationMap(context, "google.navigation:q=$lat,$lon",
                            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.NearMe, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Directions", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        // Uses the installed Google Maps client for a live, trusted
                        // hospital search without duplicating place data locally.
                        openLocationMap(context, "geo:$lat,$lon?q=hospital&z=14",
                            "https://www.google.com/maps/search/?api=1&query=${Uri.encode("hospitals near $lat,$lon")}")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.LocalHospital, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Hospitals", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        openLocationMap(context, "geo:$lat,$lon?q=emergency+services&z=14",
                            "https://www.google.com/maps/search/?api=1&query=${Uri.encode("emergency services near $lat,$lon")}")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Warning, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Emergency", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
