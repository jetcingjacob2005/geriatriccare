package com.geriatricare.app.presentation.dashboard.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geriatricare.app.data.model.EcgMeasurements
import java.util.Locale

@Composable
fun EcgMeasurementsDialog(measurements: EcgMeasurements?, onExport: (() -> Unit)? = null, onDismiss: () -> Unit) {
    fun number(value: Double?, unit: String): String = value?.let {
        String.format(Locale.US, "%.0f %s", it, unit)
    } ?: "Unavailable"
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Single-lead ECG measurements") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (onExport != null) TextButton(onClick = onExport) { Text("Export raw snapshot") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Experimental estimates — not a diagnosis. Unclear wave boundaries are not measured.")
                Text(measurements?.quality ?: "No usable measurement window")
                val rows = listOf(
                    "ECG heart rate" to number(measurements?.heartRateBpm, "bpm"),
                    "Mean RR interval" to number(measurements?.rrMs, "ms"),
                    "Short-window SDNN" to number(measurements?.sdnnMs, "ms"),
                    "Short-window RMSSD" to number(measurements?.rmssdMs, "ms"),
                    "PR interval" to number(measurements?.prMs, "ms"),
                    "QRS duration" to number(measurements?.qrsMs, "ms"),
                    "P-wave duration" to number(measurements?.pWaveMs, "ms"),
                    "QT interval" to number(measurements?.qtMs, "ms"),
                    "QTc (Fridericia)" to number(measurements?.qtcFridericiaMs, "ms"))
                for ((label, value) in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Text(value)
                }
                Text("ST interpretation: unavailable. ADC counts are not calibrated millivolts; this is one lead, not a 12-lead ECG.")
                Text("Interval estimates need ≥250 Hz, clear boundaries and at least three consistent beats. Short-window variability is not a clinical HRV assessment. The interval algorithm has not been clinically validated.")
            }
        })
}
