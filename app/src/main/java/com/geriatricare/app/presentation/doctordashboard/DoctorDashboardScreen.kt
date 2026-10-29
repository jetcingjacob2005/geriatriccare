package com.geriatricare.app.presentation.doctordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.model.*
import com.geriatricare.app.ui.theme.*
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorDashboardScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit = onBack,
    onPatientClick: (String) -> Unit = {},
    viewModel: DoctorDashboardViewModel = hiltViewModel()
) {
    val bookedPatients by viewModel.bookedPatients.collectAsState()
    val appointments by viewModel.appointments.collectAsState()
    val prescriptions by viewModel.prescriptions.collectAsState()
    val slots by viewModel.todaySlots.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Appointments", "Patients", "Medications", "Schedule")

    // Consultation & Prescribe modals
    var activeConsultationAppointment by remember { mutableStateOf<Appointment?>(null) }
    var activeConsultationPatient by remember { mutableStateOf<Patient?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chill Cheyyu Hospital",
                            color = ClinicalText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Dr. Jetcin — Hospital Dashboard",
                            color = ClinicalSubtext,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClinicalText)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.logout()
                        onLogout()
                    }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = ClinicalText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClinicalCard
                )
            )
        },
        containerColor = ClinicalPageBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = ClinicalCard,
                contentColor = ClinicalBlue,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, color = if (selectedTabIndex == index) ClinicalBlue else ClinicalSubtext) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTabIndex) {
                    0 -> OverviewTab(bookedPatients, appointments, prescriptions)
                    1 -> AppointmentsTab(
                        appointments = appointments,
                        viewModel = viewModel,
                        onConsult = { appt -> activeConsultationAppointment = appt }
                    )
                    2 -> PatientsTab(
                        patients = bookedPatients,
                        appointments = appointments,
                        onPatientClick = onPatientClick,
                        onConsult = { patient -> activeConsultationPatient = patient }
                    )
                    3 -> MedicationsTab(prescriptions, bookedPatients, viewModel)
                    4 -> ScheduleTab(slots, viewModel)
                }
            }
        }
    }

    // Modal when doctor consults & prescribes from an Appointment
    activeConsultationAppointment?.let { appt ->
        PrescribeDialog(
            patients = bookedPatients,
            initialPatientId = appt.patientId,
            initialPatientName = appt.patientName,
            appointmentId = appt.id,
            onDismiss = { activeConsultationAppointment = null },
            onPrescribe = { patientId, pName, med, dose, freq, start, end, instr, reminder, apptId ->
                viewModel.prescribeMedication(patientId, pName, med, dose, freq, start, end, instr, reminder, apptId)
                activeConsultationAppointment = null
            }
        )
    }

    // Modal when doctor consults & prescribes directly from a Patient Card
    activeConsultationPatient?.let { p ->
        PrescribeDialog(
            patients = bookedPatients,
            initialPatientId = p.id,
            initialPatientName = p.name,
            appointmentId = null,
            onDismiss = { activeConsultationPatient = null },
            onPrescribe = { patientId, pName, med, dose, freq, start, end, instr, reminder, apptId ->
                viewModel.prescribeMedication(patientId, pName, med, dose, freq, start, end, instr, reminder, apptId)
                activeConsultationPatient = null
            }
        )
    }
}

@Composable
fun OverviewTab(patients: List<Patient>, appointments: List<Appointment>, prescriptions: List<Prescription>) {
    val pendingAppointments = appointments.count { it.status == AppointmentStatus.PENDING }
    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val todayAppointments = appointments.filter { it.date == todayDate }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StatCard("Booked Patients", patients.size.toString(), Icons.Default.Person, CyanAccent) }
        item { StatCard("Pending Appts", pendingAppointments.toString(), Icons.Default.DateRange, AlertYellow) }
        item { StatCard("Today's Appts", todayAppointments.size.toString(), Icons.Default.CheckCircle, EcgGreen) }
        item { StatCard("Active Prescriptions", prescriptions.size.toString(), Icons.Default.List, SpO2Purple) }
    }
}

@Composable
fun StatCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = ClinicalCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ClinicalText)
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 14.sp, color = ClinicalSubtext, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun AppointmentsTab(
    appointments: List<Appointment>,
    viewModel: DoctorDashboardViewModel,
    onConsult: (Appointment) -> Unit
) {
    if (appointments.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = Gray500, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No appointments booked yet", color = ClinicalText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Patients who book an appointment with you will appear here.", color = Gray500, fontSize = 13.sp)
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(appointments.sortedByDescending { it.createdAt }) { appointment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ClinicalCard),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (appointment.status == AppointmentStatus.CONFIRMED) EcgGreen.copy(alpha = 0.5f) else ClinicalBorder
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(appointment.patientName, color = ClinicalText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (appointment.bookingCode.isNotBlank()) {
                                Text(appointment.bookingCode, color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        val statusColor = when (appointment.status) {
                            AppointmentStatus.PENDING -> AlertYellow
                            AppointmentStatus.CONFIRMED -> EcgGreen
                            AppointmentStatus.REJECTED -> AlertRed
                            AppointmentStatus.COMPLETED -> CyanAccent
                            AppointmentStatus.CANCELLED -> Gray500
                        }
                        Surface(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = appointment.status.name,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = ClinicalPaleBlue,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Slot: ${appointment.date} • ${appointment.timeSlot}", color = ClinicalText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (appointment.reason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Reason: ${appointment.reason}", color = Gray300, fontSize = 13.sp)
                    }
                    if (appointment.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Doctor Note: ${appointment.notes}", color = EcgGreen, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (appointment.status) {
                            AppointmentStatus.PENDING -> {
                                Button(
                                    onClick = { viewModel.confirmAppointment(appointment.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = EcgGreen),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Check, null, tint = Navy900, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Confirm", color = Navy900, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.rejectAppointment(appointment.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reject", color = White, fontWeight = FontWeight.Bold)
                                }
                            }
                            AppointmentStatus.CONFIRMED -> {
                                Button(
                                    onClick = { onConsult(appointment) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                    modifier = Modifier.weight(1.5f)
                                ) {
                                    Icon(Icons.Default.MedicalServices, null, tint = Navy900, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Consult & Prescribe", color = Navy900, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.completeAppointment(appointment.id) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = White)
                                ) {
                                    Text("Complete")
                                }
                            }
                            AppointmentStatus.COMPLETED -> {
                                Surface(
                                    color = EcgGreen.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, null, tint = EcgGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Consultation Completed", color = EcgGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PatientsTab(
    patients: List<Patient>,
    appointments: List<Appointment>,
    onPatientClick: (String) -> Unit = {},
    onConsult: (Patient) -> Unit
) {
    if (patients.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PersonOff, contentDescription = null, tint = Gray500, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No Booked Patients", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Only patients who book an appointment with you will be displayed here.",
                    color = Gray500,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(patients, key = { it.id }) { patient ->
            val patientAppts = appointments.filter { it.patientId == patient.id }
            val latestAppt = patientAppts.firstOrNull()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPatientClick(patient.id) },
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Navy600),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = patient.name.take(2).uppercase(),
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(patient.name, color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (patient.age > 0) {
                                Text("Age: ${patient.age} | ${patient.gender.ifBlank { "Patient" }}", color = Gray300, fontSize = 13.sp)
                            }
                            if (patient.condition.isNotBlank()) {
                                Text("Condition: ${patient.condition}", color = CyanAccent, fontSize = 13.sp)
                            }
                            if (patient.roomNumber.isNotBlank()) {
                                Text("Room: ${patient.roomNumber}", color = Gray500, fontSize = 12.sp)
                            }
                        }
                    }

                    if (latestAppt != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = Navy800.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Slot: ${latestAppt.date} (${latestAppt.timeSlot})", color = Gray300, fontSize = 12.sp)
                                Text(
                                    latestAppt.status.name,
                                    color = if (latestAppt.status == AppointmentStatus.CONFIRMED) EcgGreen else AlertYellow,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onConsult(patient) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MedicalServices, null, tint = Navy900, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Consult & Prescribe", color = Navy900, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MedicationsTab(prescriptions: List<Prescription>, patients: List<Patient>, viewModel: DoctorDashboardViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    var prescriptionToDelete by remember { mutableStateOf<Prescription?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (prescriptions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.List, contentDescription = null, tint = Gray500, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No prescriptions issued yet", color = Gray300, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Prescribe medications during patient consultation.", color = Gray500, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 80.dp)) {
                items(prescriptions) { prescription ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(prescription.medicationName, color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = EcgGreen.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "ACTIVE",
                                            color = EcgGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { prescriptionToDelete = prescription },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Prescription",
                                            tint = AlertRed.copy(alpha = 0.85f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Patient: ${prescription.patientName}", color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Dosage: ${prescription.dosage} • Frequency: ${prescription.frequency}", color = Gray300, fontSize = 13.sp)
                            if (prescription.instructions.isNotBlank()) {
                                Text("Instructions: ${prescription.instructions}", color = Gray300, fontSize = 13.sp)
                            }
                            if (prescription.reminderTime.isNotBlank()) {
                                Text("Reminders scheduled: ${prescription.reminderTime}", color = CyanAccent, fontSize = 12.sp)
                            }
                            Text("Duration: ${prescription.startDate} to ${prescription.endDate}", color = Gray500, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        prescriptionToDelete?.let { pres ->
            AlertDialog(
                onDismissRequest = { prescriptionToDelete = null },
                containerColor = Navy800,
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = AlertRed) },
                title = { Text("Delete Prescription?", color = White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Are you sure you want to delete ${pres.medicationName} (${pres.dosage}) for ${pres.patientName}?\nThis will remove all associated medication reminders.",
                        color = Gray300,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deletePrescription(pres.id)
                            prescriptionToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                    ) {
                        Text("Delete", color = White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { prescriptionToDelete = null }) {
                        Text("Cancel", color = Gray300)
                    }
                }
            )
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            containerColor = CyanAccent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Prescription", tint = Navy900)
        }

        if (showDialog) {
            PrescribeDialog(
                patients = patients,
                appointmentId = null,
                onDismiss = { showDialog = false },
                onPrescribe = { patientId, pName, med, dose, freq, start, end, instr, reminder, _ ->
                    viewModel.prescribeMedication(patientId, pName, med, dose, freq, start, end, instr, reminder)
                    showDialog = false
                }
            )
        }
    }
}

enum class MealTiming { NONE, BEFORE_MEAL, AFTER_MEAL }

@Composable
private fun MealSlotRow(
    icon: String,
    title: String,
    beforeLabel: String,
    afterLabel: String,
    selectedTiming: MealTiming,
    customTime: String,
    onTimingChange: (MealTiming) -> Unit,
    onPickTime: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
            }
            if (selectedTiming != MealTiming.NONE) {
                Surface(
                    color = CyanAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { onPickTime() }
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccessTime, null, tint = CyanAccent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(customTime, style = MaterialTheme.typography.labelSmall, color = CyanAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selectedTiming == MealTiming.NONE,
                onClick = { onTimingChange(MealTiming.NONE) },
                label = { Text("Off", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = selectedTiming == MealTiming.BEFORE_MEAL,
                onClick = { onTimingChange(MealTiming.BEFORE_MEAL) },
                label = { Text(beforeLabel, style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = selectedTiming == MealTiming.AFTER_MEAL,
                onClick = { onTimingChange(MealTiming.AFTER_MEAL) },
                label = { Text(afterLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescribeDialog(
    patients: List<Patient>,
    initialPatientId: String = "",
    initialPatientName: String = "",
    appointmentId: String? = null,
    onDismiss: () -> Unit,
    onPrescribe: (
        patientId: String,
        patientName: String,
        medicationName: String,
        dosage: String,
        frequency: String,
        startDate: String,
        endDate: String,
        instructions: String,
        reminderTime: String,
        appointmentId: String?
    ) -> Unit
) {
    val context = LocalContext.current
    val defaultPatient = patients.firstOrNull { it.id == initialPatientId } ?: patients.firstOrNull()
    var selectedPatientId by remember { mutableStateOf(initialPatientId.ifBlank { defaultPatient?.id.orEmpty() }) }
    var selectedPatientName by remember { mutableStateOf(initialPatientName.ifBlank { defaultPatient?.name.orEmpty() }) }

    // Calendar Dates & Duration
    val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var startDate by remember { mutableStateOf(todayStr) }
    var selectedDurationDays by remember { mutableIntStateOf(7) }
    val durationOptions = listOf(3, 5, 7, 14, 30)

    val initialEndDate = remember(startDate, selectedDurationDays) {
        val cal = Calendar.getInstance()
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(startDate)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {}
        cal.add(Calendar.DAY_OF_YEAR, selectedDurationDays)
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
    var endDate by remember { mutableStateOf(initialEndDate) }

    var medName by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("1 Tablet") }

    // Meal Timings & Scheduling
    var morningTiming by remember { mutableStateOf(MealTiming.AFTER_MEAL) }
    var noonTiming by remember { mutableStateOf(MealTiming.NONE) }
    var nightTiming by remember { mutableStateOf(MealTiming.AFTER_MEAL) }
    var bedtimeEnabled by remember { mutableStateOf(false) }

    var morningTime by remember { mutableStateOf("08:30") }
    var noonTime by remember { mutableStateOf("13:30") }
    var nightTime by remember { mutableStateOf("20:30") }
    var bedtimeTime by remember { mutableStateOf("21:30") }

    var frequency by remember { mutableStateOf("Morning (After Breakfast) + Night (After Dinner)") }
    var instructions by remember { mutableStateOf("Take after breakfast in morning and after dinner at night with water") }
    var reminder by remember { mutableStateOf("08:30, 20:30") }

    var expanded by remember { mutableStateOf(false) }

    val commonMeds = listOf(
        "Metformin 500mg",
        "Amlodipine 5mg",
        "Atorvastatin 10mg",
        "Paracetamol 650mg",
        "Pantoprazole 40mg",
        "Telmisartan 40mg"
    )

    // Recompute plan when meal timing or custom times change
    LaunchedEffect(morningTiming, noonTiming, nightTiming, bedtimeEnabled, morningTime, noonTime, nightTime, bedtimeTime) {
        val freqParts = mutableListOf<String>()
        val instrParts = mutableListOf<String>()
        val times = mutableListOf<String>()

        if (morningTiming == MealTiming.BEFORE_MEAL) {
            freqParts.add("Morning (Before Breakfast)")
            instrParts.add("before breakfast in morning")
            times.add(morningTime)
        } else if (morningTiming == MealTiming.AFTER_MEAL) {
            freqParts.add("Morning (After Breakfast)")
            instrParts.add("after breakfast in morning")
            times.add(morningTime)
        }

        if (noonTiming == MealTiming.BEFORE_MEAL) {
            freqParts.add("Noon (Before Lunch)")
            instrParts.add("before lunch at noon")
            times.add(noonTime)
        } else if (noonTiming == MealTiming.AFTER_MEAL) {
            freqParts.add("Noon (After Lunch)")
            instrParts.add("after lunch at noon")
            times.add(noonTime)
        }

        if (nightTiming == MealTiming.BEFORE_MEAL) {
            freqParts.add("Night (Before Dinner)")
            instrParts.add("before dinner at night")
            times.add(nightTime)
        } else if (nightTiming == MealTiming.AFTER_MEAL) {
            freqParts.add("Night (After Dinner)")
            instrParts.add("after dinner at night")
            times.add(nightTime)
        }

        if (bedtimeEnabled) {
            freqParts.add("Bedtime")
            instrParts.add("at bedtime before sleep")
            times.add(bedtimeTime)
        }

        frequency = if (freqParts.isNotEmpty()) freqParts.joinToString(" + ") else "As needed"
        instructions = if (instrParts.isNotEmpty()) "Take " + instrParts.joinToString(" and ") + " with water" else "Take as directed by doctor"
        reminder = times.joinToString(", ")
    }

    // Calendar Date Dialogs
    fun showStartDatePicker() {
        val cal = Calendar.getInstance()
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(startDate)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {}

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                startDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selected.time)
                selected.add(Calendar.DAY_OF_YEAR, selectedDurationDays)
                endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selected.time)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun showEndDatePicker() {
        val cal = Calendar.getInstance()
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(endDate)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {}

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selected.time)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // Time Picker Dialog
    fun showTimePicker(currentTime: String, onTimePicked: (String) -> Unit) {
        val parts = currentTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                val formatted = "%02d:%02d".format(hourOfDay, minute)
                onTimePicked(formatted)
            },
            h,
            m,
            true
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (appointmentId != null) "Clinical Consultation" else "Prescribe Medication",
                    color = White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Prescribing for $selectedPatientName",
                    color = CyanAccent,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Patient Selector (if multiple and not launched from specific patient)
                if (patients.size > 1 && initialPatientId.isBlank()) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedPatientName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Patient") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = White, unfocusedTextColor = White
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            patients.forEach { patient ->
                                DropdownMenuItem(
                                    text = { Text(patient.name) },
                                    onClick = {
                                        selectedPatientId = patient.id
                                        selectedPatientName = patient.name
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Quick Med Suggestions
                Text("Quick Select Medication", style = MaterialTheme.typography.labelSmall, color = Gray500)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(commonMeds) { med ->
                        FilterChip(
                            selected = medName == med,
                            onClick = { medName = med },
                            label = { Text(med, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = medName,
                    onValueChange = { medName = it },
                    label = { Text("Medication Name & Strength") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage (e.g. 1 Tablet, 5ml)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── Date & Duration Picker (Calendar) ───────────────────
                Surface(
                    color = Navy900.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DateRange, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Dates (Pick from Calendar)", style = MaterialTheme.typography.labelMedium, color = CyanAccent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))

                        Text("Quick Duration", style = MaterialTheme.typography.labelSmall, color = Gray500)
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(durationOptions) { days ->
                                FilterChip(
                                    selected = selectedDurationDays == days,
                                    onClick = {
                                        selectedDurationDays = days
                                        val cal = Calendar.getInstance()
                                        try {
                                            val p = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(startDate)
                                            if (p != null) cal.time = p
                                        } catch (_: Exception) {}
                                        cal.add(Calendar.DAY_OF_YEAR, days)
                                        endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                                    },
                                    label = { Text("$days Days", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showStartDatePicker() },
                                shape = RoundedCornerShape(10.dp),
                                color = Navy800,
                                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("Start Date (Tap)", style = MaterialTheme.typography.labelSmall, color = Gray500)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DateRange, null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(startDate, color = White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showEndDatePicker() },
                                shape = RoundedCornerShape(10.dp),
                                color = Navy800,
                                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("End Date (Tap)", style = MaterialTheme.typography.labelSmall, color = Gray500)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DateRange, null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(endDate, color = White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Meal Timing & Frequency ─────────────────────────────
                Surface(
                    color = Navy900.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Frequency & Meal Timing", style = MaterialTheme.typography.labelMedium, color = CyanAccent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))

                        // Morning Slot
                        MealSlotRow(
                            icon = "🌅",
                            title = "Morning",
                            beforeLabel = "Before Breakfast",
                            afterLabel = "After Breakfast",
                            selectedTiming = morningTiming,
                            customTime = morningTime,
                            onTimingChange = {
                                morningTiming = it
                                if (it == MealTiming.BEFORE_MEAL && morningTime == "08:30") morningTime = "07:30"
                                else if (it == MealTiming.AFTER_MEAL && morningTime == "07:30") morningTime = "08:30"
                            },
                            onPickTime = {
                                showTimePicker(morningTime) { morningTime = it }
                            }
                        )

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Navy800)
                        Spacer(Modifier.height(10.dp))

                        // Noon Slot
                        MealSlotRow(
                            icon = "☀️",
                            title = "Noon / Lunch",
                            beforeLabel = "Before Lunch",
                            afterLabel = "After Lunch",
                            selectedTiming = noonTiming,
                            customTime = noonTime,
                            onTimingChange = {
                                noonTiming = it
                                if (it == MealTiming.BEFORE_MEAL && noonTime == "13:30") noonTime = "12:30"
                                else if (it == MealTiming.AFTER_MEAL && noonTime == "12:30") noonTime = "13:30"
                            },
                            onPickTime = {
                                showTimePicker(noonTime) { noonTime = it }
                            }
                        )

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Navy800)
                        Spacer(Modifier.height(10.dp))

                        // Night Slot
                        MealSlotRow(
                            icon = "🌙",
                            title = "Night / Dinner",
                            beforeLabel = "Before Dinner",
                            afterLabel = "After Dinner",
                            selectedTiming = nightTiming,
                            customTime = nightTime,
                            onTimingChange = {
                                nightTiming = it
                                if (it == MealTiming.BEFORE_MEAL && nightTime == "20:30") nightTime = "19:30"
                                else if (it == MealTiming.AFTER_MEAL && nightTime == "19:30") nightTime = "20:30"
                            },
                            onPickTime = {
                                showTimePicker(nightTime) { nightTime = it }
                            }
                        )

                        Spacer(Modifier.height(8.dp))
                        // Bedtime option
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🛌", fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text("Bedtime", style = MaterialTheme.typography.bodySmall, color = White)
                                if (bedtimeEnabled) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        color = CyanAccent.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable {
                                            showTimePicker(bedtimeTime) { bedtimeTime = it }
                                        }
                                    ) {
                                        Text(
                                            bedtimeTime,
                                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Switch(
                                checked = bedtimeEnabled,
                                onCheckedChange = { bedtimeEnabled = it }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Frequency Summary (Auto-generated from meal slots, editable)
                OutlinedTextField(
                    value = frequency,
                    onValueChange = { frequency = it },
                    label = { Text("Prescription Frequency") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Doctor Instructions (Auto-generated, editable)
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Doctor Instructions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Patient Reminders (Auto-generated from timing badges, editable)
                OutlinedTextField(
                    value = reminder,
                    onValueChange = { reminder = it },
                    label = { Text("Scheduled Reminders (e.g. 08:30, 20:30)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPrescribe(
                        selectedPatientId,
                        selectedPatientName,
                        medName.trim(),
                        dosage.trim(),
                        frequency.trim(),
                        startDate.trim(),
                        endDate.trim(),
                        instructions.trim(),
                        reminder.trim(),
                        appointmentId
                    )
                },
                enabled = medName.isNotBlank() && selectedPatientId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text(
                    if (appointmentId != null) "Complete & Prescribe" else "Prescribe",
                    color = Navy900,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Gray300)
            }
        },
        containerColor = Navy800
    )
}

@Composable
fun ScheduleTab(slots: List<AppointmentSlot>, viewModel: DoctorDashboardViewModel) {
    var dateStr by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = dateStr,
                onValueChange = { dateStr = it },
                label = { Text("Date (YYYY-MM-DD)", color = Gray300) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.createDefaultSlots(dateStr) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text("Generate", color = Navy900)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(slots) { slot ->
                val (color, label) = when (slot.status) {
                    SlotStatus.AVAILABLE -> EcgGreen to "AVAIL"
                    SlotStatus.BOOKED -> CyanAccent to "BOOKED"
                    SlotStatus.BLOCKED -> AlertRed to "BLOCKED"
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = slot.status != SlotStatus.BOOKED) {
                            if (slot.status == SlotStatus.AVAILABLE) viewModel.blockSlot(slot.id)
                            else if (slot.status == SlotStatus.BLOCKED) viewModel.releaseSlot(slot.id)
                        },
                    colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(slot.timeSlot, color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
