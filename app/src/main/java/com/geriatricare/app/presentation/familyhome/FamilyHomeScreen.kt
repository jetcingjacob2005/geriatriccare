package com.geriatricare.app.presentation.familyhome

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyHomeScreen(
    onPatientClick: (String) -> Unit,
    onLogout      : () -> Unit,
    viewModel     : FamilyHomeViewModel = hiltViewModel()
) {
    val patients by viewModel.linkedPatients.collectAsState()

    Scaffold(
        containerColor = ClinicalPageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("JeevaMitra", style = MaterialTheme.typography.titleLarge, color = ClinicalText, fontWeight = FontWeight.Bold)
                            Text("Family View", style = MaterialTheme.typography.labelSmall, color = ClinicalSubtext)
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
        }
    ) { padding ->
        if (patients.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.PersonSearch, null, tint = Gray500, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No linked patient yet", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ask the caregiver to add this account's email as a family contact on the patient's profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(patients, key = { it.id }) { patient ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onPatientClick(patient.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = ClinicalCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
                        tonalElevation = 2.dp
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(CyanAccent.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, null, tint = CyanAccent)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(patient.name.ifBlank { "Patient" }, style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        patient.age.takeIf { it > 0 }?.let { "$it yrs" },
                                        patient.condition.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = Gray500
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Gray500)
                        }
                    }
                }
            }
        }
    }
}
