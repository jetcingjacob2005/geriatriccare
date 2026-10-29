package com.geriatricare.app.presentation.editpatient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.presentation.addpatient.FormTextField
import com.geriatricare.app.presentation.addpatient.SectionHeader
import com.geriatricare.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPatientScreen(
    onBack       : () -> Unit,
    onSaved      : () -> Unit,
    viewModel    : EditPatientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var name             by remember { mutableStateOf("") }
    var age              by remember { mutableStateOf("") }
    var gender           by remember { mutableStateOf("Male") }
    var condition        by remember { mutableStateOf("") }
    var room             by remember { mutableStateOf("") }
    var bloodGroup       by remember { mutableStateOf("") }
    var emergencyContact by remember { mutableStateOf("") }
    var emergencyPhone   by remember { mutableStateOf("") }
    var doctorName       by remember { mutableStateOf("") }
    var doctorPhone      by remember { mutableStateOf("") }
    var doctorEmail      by remember { mutableStateOf("") }
    var hospitalName     by remember { mutableStateOf("") }
    var familyEmails     by remember { mutableStateOf("") }
    var prefilled        by remember { mutableStateOf(false) }

    val genderOptions = listOf("Male", "Female", "Other")
    var genderMenuExpanded by remember { mutableStateOf(false) }

    // Pre-fill the form once the patient loads (only once, so it doesn't
    // clobber in-progress edits if the state flow re-emits).
    LaunchedEffect(state.patient) {
        val p = state.patient ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        name = p.name
        age = if (p.age > 0) p.age.toString() else ""
        gender = p.gender.ifBlank { "Male" }
        condition = p.condition
        room = p.roomNumber
        bloodGroup = p.bloodGroup
        emergencyContact = p.emergencyContact
        emergencyPhone = p.emergencyPhone
        doctorName = p.doctorName.ifBlank { "Dr. Jetcin" }
        doctorPhone = p.doctorPhone.ifBlank { "+919400199999" }
        doctorEmail = p.doctorEmail.ifBlank { "jetcin@chillcheyyu.com" }
        hospitalName = p.hospitalName.ifBlank { "Chill Cheyyu Hospital" }
        familyEmails = p.familyEmails.joinToString(", ")
        prefilled = true
    }

    LaunchedEffect(state.isSaved) { if (state.isSaved) onSaved() }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = White) }
                },
                title = { Text("Edit Patient", style = MaterialTheme.typography.titleLarge, color = White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy800)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyanAccent)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("Personal Information")

            FormTextField("Full Name", name, { name = it }, Icons.Default.Person, KeyboardType.Text)
            FormTextField("Age", age, { age = it }, Icons.Default.Cake, KeyboardType.Number)

            ExposedDropdownMenuBox(expanded = genderMenuExpanded, onExpandedChange = { genderMenuExpanded = it }) {
                OutlinedTextField(
                    value = gender,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Gender") },
                    leadingIcon = { Icon(Icons.Default.Wc, null, tint = CyanAccent) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genderMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = com.geriatricare.app.presentation.addpatient.fieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = genderMenuExpanded,
                    onDismissRequest = { genderMenuExpanded = false },
                    modifier = Modifier.background(Navy700)
                ) {
                    genderOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt, color = White) },
                            onClick = { gender = opt; genderMenuExpanded = false }
                        )
                    }
                }
            }

            FormTextField("Blood Group (e.g. O+)", bloodGroup, { bloodGroup = it }, Icons.Default.Bloodtype, KeyboardType.Text)

            Spacer(Modifier.height(4.dp))
            SectionHeader("Medical Details")

            FormTextField("Primary Condition / Diagnosis", condition, { condition = it }, Icons.Default.MedicalServices, KeyboardType.Text)
            FormTextField("Room Number", room, { room = it }, Icons.Default.MeetingRoom, KeyboardType.Text)

            Spacer(Modifier.height(4.dp))
            SectionHeader("Emergency Contact")

            FormTextField("Contact Name", emergencyContact, { emergencyContact = it }, Icons.Default.ContactPhone, KeyboardType.Text)
            FormTextField("Phone Number", emergencyPhone, { emergencyPhone = it }, Icons.Default.Phone, KeyboardType.Phone, ImeAction.Next)

            Spacer(Modifier.height(4.dp))
            SectionHeader("Doctor / Hospital (optional)")

            FormTextField("Doctor Name", doctorName, { doctorName = it }, Icons.Default.MedicalServices, KeyboardType.Text)
            FormTextField("Doctor Phone", doctorPhone, { doctorPhone = it }, Icons.Default.Phone, KeyboardType.Phone)
            FormTextField("Doctor Email", doctorEmail, { doctorEmail = it }, Icons.Default.Email, KeyboardType.Email)
            FormTextField("Hospital Name", hospitalName, { hospitalName = it }, Icons.Default.LocalHospital, KeyboardType.Text, ImeAction.Next)

            Spacer(Modifier.height(4.dp))
            SectionHeader("Family Access (optional)")
            FormTextField(
                "Family/Child Email(s) — comma separated",
                familyEmails, { familyEmails = it },
                Icons.Default.FamilyRestroom, KeyboardType.Email, ImeAction.Done
            )

            if (state.error.isNotEmpty()) {
                Surface(color = AlertRed.copy(0.12f), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = AlertRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.error, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.savePatient(
                        name, age, gender, condition, room, bloodGroup,
                        emergencyContact, emergencyPhone,
                        doctorName, doctorPhone, doctorEmail, hospitalName,
                        familyEmails
                    )
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900),
                enabled  = !state.isSaving
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Navy900, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Changes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
