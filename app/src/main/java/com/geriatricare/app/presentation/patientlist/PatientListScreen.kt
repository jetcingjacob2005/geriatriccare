package com.geriatricare.app.presentation.patientlist

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListScreen(
    onPatientClick: (String) -> Unit,
    onAddPatient  : () -> Unit,
    onLogout      : () -> Unit,
    viewModel     : PatientListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = ClinicalPageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("JeevaMitra", style = MaterialTheme.typography.titleLarge,
                                color = ClinicalText, fontWeight = FontWeight.Bold)
                            Text("Patient Management", style = MaterialTheme.typography.labelSmall, color = ClinicalSubtext)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.logout(); onLogout() }) {
                        Icon(Icons.Default.ExitToApp, "Logout", tint = Gray500)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClinicalCard)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick           = onAddPatient,
                containerColor    = CyanAccent,
                contentColor      = Navy900,
                icon = { Icon(Icons.Default.PersonAdd, null) },
                text = { Text("Add Patient", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding      = PaddingValues(vertical = 16.dp)
        ) {
            // Summary Stats
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatChip("Total Patients", "${state.patients.size}", CyanAccent, Icons.Default.Group, Modifier.weight(1f))
                    StatChip("Monitoring", "${state.patients.count { it.bleDeviceAddress.isNotEmpty() }}", EcgGreen, Icons.Default.Sensors, Modifier.weight(1f))
                    StatChip("Alerts", "${state.alertCount}", HeartRed, Icons.Default.NotificationsActive, Modifier.weight(1f))
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("Patients", style = MaterialTheme.typography.titleMedium,
                    color = ClinicalText, fontWeight = FontWeight.SemiBold)
            }

            // Loading
            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = CyanAccent)
                    }
                }
            }

            // Patient cards
            items(state.patients, key = { it.id }) { patient ->
                PatientCard(patient = patient, onClick = { onPatientClick(patient.id) })
            }

            // Empty state
            if (!state.isLoading && state.patients.isEmpty()) {
                item {
                    EmptyState()
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = ClinicalCard, border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder)) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.size(32.dp).background(color.copy(0.15f), CircleShape), Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = ClinicalText, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Gray500)
        }
    }
}

@Composable
private fun PatientCard(patient: Patient, onClick: () -> Unit) {
    val initials = patient.name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")

    Surface(
        modifier       = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape          = RoundedCornerShape(18.dp),
        color          = ClinicalCard,
        border         = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Avatar with gradient
            Box(
                modifier = Modifier.size(54.dp)
                    .background(Brush.linearGradient(listOf(CyanAccent, SpO2Purple)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(patient.name, style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        if (patient.age > 0) append("${patient.age} yrs • ")
                        if (patient.gender.isNotEmpty()) append("${patient.gender} • ")
                        append(patient.condition.ifEmpty { "No diagnosis" })
                    },
                    style = MaterialTheme.typography.bodySmall, color = Gray500
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (patient.roomNumber.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MeetingRoom, null, tint = CyanAccent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Room ${patient.roomNumber}", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                        }
                    }
                    if (patient.bleDeviceAddress.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, null, tint = EcgGreen, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("BLE Assigned", style = MaterialTheme.typography.labelSmall, color = EcgGreen)
                        }
                    }
                }
            }

            Icon(Icons.Default.ChevronRight, null, tint = Gray700)
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PersonOff, null, tint = Gray700, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("No patients yet", style = MaterialTheme.typography.titleMedium, color = Gray500)
            Text("Tap + to add your first patient", style = MaterialTheme.typography.bodySmall, color = Gray700)
        }
    }
}
