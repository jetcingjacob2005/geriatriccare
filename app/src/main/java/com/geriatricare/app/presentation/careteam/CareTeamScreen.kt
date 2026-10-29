package com.geriatricare.app.presentation.careteam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import coil.compose.AsyncImage
import com.geriatricare.app.data.doctor.DoctorDirectory
import com.geriatricare.app.data.model.CareRequest
import com.geriatricare.app.data.model.CareRequestStatus
import com.geriatricare.app.data.model.CareRequestType
import com.geriatricare.app.data.model.Doctor
import com.geriatricare.app.data.report.WhatsAppSender
import com.geriatricare.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareTeamScreen(
    onBack   : () -> Unit,
    viewModel: CareTeamViewModel = hiltViewModel()
) {
    val uiState  by viewModel.uiState.collectAsState()
    val requests by viewModel.requests.collectAsState()
    val patient  = uiState.patient
    val lastBooked by viewModel.lastBookedAppointment.collectAsState()
    val ctx = LocalContext.current

    var messageText by remember { mutableStateOf("") }
    var appointmentDate by remember { mutableStateOf("") }
    var appointmentReason by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(1) } // 0 = Message, 1 = Appointment (booking is the primary entry point now)
    var selectedDoctor by remember { mutableStateOf<Doctor?>(null) }
    var selectedSlot by remember { mutableStateOf<String?>(null) }
    var showDoctorPicker by remember { mutableStateOf(false) }

    fun resetBookingForm() {
        appointmentDate = ""
        appointmentReason = ""
        selectedDoctor = null
        selectedSlot = null
    }

    if (lastBooked != null) {
        AppointmentConfirmationDialog(
            request = lastBooked!!,
            patientName = patient?.name.orEmpty(),
            onDismiss = { viewModel.dismissBookingConfirmation() }
        )
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            TopAppBar(
                title = { Text("Care Team", color = White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Doctor / Hospital contact card ───────────────────────────────
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = CardBackground,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Doctor / Hospital", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        InfoLine("Doctor", patient?.doctorName?.ifBlank { "Dr. Jetcin" } ?: "Dr. Jetcin")
                        InfoLine("Hospital", patient?.hospitalName?.ifBlank { "Chill Cheyyu Hospital" } ?: "Chill Cheyyu Hospital")
                        InfoLine("Phone", patient?.doctorPhone?.ifBlank { "+919400199999" } ?: "+919400199999")
                        InfoLine("Email", patient?.doctorEmail?.ifBlank { "jetcin@chillcheyyu.com" } ?: "jetcin@chillcheyyu.com")

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val phone = patient?.doctorPhone?.ifBlank { "+919400199999" } ?: "+919400199999"
                            OutlinedButton(
                                onClick = {
                                    ctx.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))
                                    )
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Call")
                            }
                            val email = patient?.doctorEmail?.ifBlank { "jetcin@chillcheyyu.com" } ?: "jetcin@chillcheyyu.com"
                            OutlinedButton(
                                onClick = {
                                    ctx.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$email"))
                                    )
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Email, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Email")
                            }
                        }
                    }
                }
            }

            // ── Send Message / Book Appointment ───────────────────────────────
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = CardBackground,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Transparent,
                            contentColor = White
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Message") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Book Appointment") })
                        }
                        Spacer(Modifier.height(14.dp))

                        if (tab == 0) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                label = { Text("Message to doctor") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    viewModel.sendMessage(messageText)
                                    messageText = ""
                                },
                                enabled = messageText.isNotBlank() && !uiState.isSending,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Send Message")
                            }
                        } else {
                            if (selectedDoctor == null) {
                                FindDoctorPrompt(onClick = { showDoctorPicker = true })
                            } else {
                                DoctorBookingPanel(
                                    doctor            = selectedDoctor!!,
                                    selectedSlot      = selectedSlot,
                                    onSlotSelected    = { slot ->
                                        selectedSlot = slot
                                        appointmentDate = "Today, $slot"
                                    },
                                    appointmentDate   = appointmentDate,
                                    onDateChange      = { appointmentDate = it },
                                    appointmentReason = appointmentReason,
                                    onReasonChange    = { appointmentReason = it },
                                    isSending         = uiState.isSending,
                                    onChangeDoctor    = { showDoctorPicker = true },
                                    onSendRequest     = {
                                        viewModel.requestAppointment(
                                            preferredDate = appointmentDate.ifBlank { "Today" },
                                            reason        = appointmentReason,
                                            doctorName    = selectedDoctor?.name.orEmpty(),
                                            hospitalName  = selectedDoctor?.hospital.orEmpty(),
                                            specialty     = selectedDoctor?.specialty.orEmpty(),
                                            doctorPhone   = selectedDoctor?.phone.orEmpty(),
                                            timeSlot      = selectedSlot.orEmpty()
                                        )
                                        resetBookingForm()
                                    },
                                    onCallHospital    = {
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_DIAL,
                                                android.net.Uri.parse("tel:${selectedDoctor!!.phone}")
                                            )
                                        )
                                    },
                                    onWhatsAppHospital = {
                                        val doc = selectedDoctor!!
                                        val text = "Hi, I'd like to book an appointment with ${doc.name} (${doc.specialty}) at ${doc.hospital}" +
                                            (if (selectedSlot != null) " — preferred slot: $selectedSlot today." else ".")
                                        WhatsAppSender.openChat(ctx, doc.phone, text)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── History ───────────────────────────────────────────────────────
            item {
                Text("History", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.SemiBold)
            }
            if (requests.isEmpty()) {
                item {
                    Text("No messages or appointment requests yet.", style = MaterialTheme.typography.bodySmall, color = Gray500)
                }
            } else {
                items(requests, key = { it.id }) { req ->
                    CareRequestRow(req, onStatusChange = { viewModel.updateStatus(req.id, it) })
                }
            }
        }
    }

    if (showDoctorPicker) {
        DoctorPickerDialog(
            onDismiss = { showDoctorPicker = false },
            onSelect  = { doc ->
                selectedDoctor = doc
                selectedSlot = null
                showDoctorPicker = false
            }
        )
    }
}

// ── Booking sub-composables ────────────────────────────────────────────────

@Composable
private fun FindDoctorPrompt(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Navy600,
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(CyanAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LocalHospital, null, tint = CyanAccent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Find a doctor near you", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
                Text("Browse doctors across Trivandrum hospitals", style = MaterialTheme.typography.labelSmall, color = Gray500)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Gray500)
        }
    }
}

@Composable
private fun DoctorBookingPanel(
    doctor            : Doctor,
    selectedSlot      : String?,
    onSlotSelected    : (String) -> Unit,
    appointmentDate   : String,
    onDateChange      : (String) -> Unit,
    appointmentReason : String,
    onReasonChange    : (String) -> Unit,
    isSending         : Boolean,
    onChangeDoctor    : () -> Unit,
    onSendRequest     : () -> Unit,
    onCallHospital    : () -> Unit,
    onWhatsAppHospital: () -> Unit
) {
    Column {
        // ── Doctor header ────────────────────────────────────────────────
        Surface(shape = RoundedCornerShape(16.dp), color = Navy600, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = doctor.avatarUrl,
                    contentDescription = doctor.name,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Navy500)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(doctor.name, style = MaterialTheme.typography.titleSmall, color = White, fontWeight = FontWeight.Bold)
                    Text(doctor.specialty, style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    Spacer(Modifier.height(2.dp))
                    Text(doctor.hospital, style = MaterialTheme.typography.labelSmall, color = Gray500)
                    Text(doctor.locality, style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = AlertYellow, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("${doctor.rating}", style = MaterialTheme.typography.labelSmall, color = White, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onChangeDoctor, contentPadding = PaddingValues(0.dp)) {
                        Text("Change", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Slot picker ──────────────────────────────────────────────────
        Text("Available slots today", style = MaterialTheme.typography.labelMedium, color = Gray500, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (doctor.slots.isEmpty()) {
            Text("No slots listed — request a preferred time below.", style = MaterialTheme.typography.labelSmall, color = Gray500)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(doctor.slots) { slot ->
                    FilterChip(
                        selected = slot == selectedSlot,
                        onClick = { onSlotSelected(slot) },
                        label = { Text(slot, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = if (slot == selectedSlot) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Quick Reason", style = MaterialTheme.typography.labelSmall, color = Gray500)
        Spacer(Modifier.height(6.dp))
        val quickReasons = listOf(
            "Routine Geriatric Checkup",
            "Post-Fall Review",
            "Cardiology & ECG Review",
            "Vital Variation Check",
            "Prescription Refill"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quickReasons) { reason ->
                FilterChip(
                    selected = appointmentReason == reason,
                    onClick = { onReasonChange(reason) },
                    label = { Text(reason, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = appointmentDate,
            onValueChange = onDateChange,
            label = { Text("Date / time (edit if needed)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = appointmentReason,
            onValueChange = onReasonChange,
            label = { Text("Reason (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSendRequest,
            enabled = (appointmentDate.isNotBlank() || selectedSlot != null) && !isSending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.EventAvailable, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Send Appointment Request")
        }

        Spacer(Modifier.height(10.dp))
        Text("Or connect with the hospital directly", style = MaterialTheme.typography.labelSmall, color = Gray500)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCallHospital, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call Hospital")
            }
            OutlinedButton(
                onClick = onWhatsAppHospital,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("WhatsApp")
            }
        }
    }
}

@Composable
private fun DoctorPickerDialog(onDismiss: () -> Unit, onSelect: (Doctor) -> Unit) {
    var selectedSpecialty by remember { mutableStateOf("All") }
    val doctors = remember(selectedSpecialty) { DoctorDirectory.bySpecialty(selectedSpecialty) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Navy700, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Text("Doctors near you", style = MaterialTheme.typography.titleLarge, color = White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("Thiruvananthapuram, Kerala", style = MaterialTheme.typography.bodySmall, color = Gray500)
                Spacer(Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(DoctorDirectory.specialties) { spec ->
                        FilterChip(
                            selected = spec == selectedSpecialty,
                            onClick = { selectedSpecialty = spec },
                            label = { Text(spec, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(doctors, key = { it.id }) { doc ->
                        DoctorListItem(doc, onClick = { onSelect(doc) })
                    }
                    if (doctors.isEmpty()) {
                        item {
                            Text("No doctors found for this specialty.", color = Gray500, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close", color = Gray500)
                }
            }
        }
    }
}

@Composable
private fun DoctorListItem(doctor: Doctor, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Navy600,
        onClick = onClick
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = doctor.avatarUrl,
                contentDescription = doctor.name,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Navy500)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(doctor.name, style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
                        Text(doctor.specialty, style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = AlertYellow, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("${doctor.rating}", style = MaterialTheme.typography.labelSmall, color = White)
                        Spacer(Modifier.width(6.dp))
                        Text("• ${doctor.experienceYears} yrs", style = MaterialTheme.typography.labelSmall, color = Gray500)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(doctor.hospital, style = MaterialTheme.typography.labelSmall, color = Gray500)
                Text("${doctor.locality} • ${doctor.availability}", style = MaterialTheme.typography.labelSmall, color = Gray700)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.labelMedium, color = Gray500)
        Text(value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.labelMedium, color = White)
    }
}

@Composable
private fun CareRequestRow(req: CareRequest, onStatusChange: (CareRequestStatus) -> Unit) {
    val ctx = LocalContext.current
    val statusColor = when (req.status) {
        CareRequestStatus.PENDING   -> AlertYellow
        CareRequestStatus.CONFIRMED -> EcgGreen
        CareRequestStatus.DECLINED  -> AlertRed
        CareRequestStatus.COMPLETED -> Gray500
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (req.type == CareRequestType.APPOINTMENT) "📅 Appointment" else "💬 Message",
                        style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold
                    )
                    if (req.bookingCode.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = CyanAccent.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                            Text(
                                req.bookingCode,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Surface(color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        req.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (req.type == CareRequestType.APPOINTMENT && req.requestedDate.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Slot: ${req.requestedDate}", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.Medium)
            }
            if (req.doctorName.isNotBlank()) {
                Text(
                    "With ${req.doctorName}${if (req.specialty.isNotBlank()) " (${req.specialty})" else ""}${if (req.hospitalName.isNotBlank()) " — ${req.hospitalName}" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )
            }
            if (req.message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Reason: ${req.message}", style = MaterialTheme.typography.bodySmall, color = Gray500)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (req.type == CareRequestType.APPOINTMENT) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                data = CalendarContract.Events.CONTENT_URI
                                putExtra(CalendarContract.Events.TITLE, "Doctor Consultation: ${req.doctorName}")
                                putExtra(CalendarContract.Events.EVENT_LOCATION, req.hospitalName)
                                putExtra(CalendarContract.Events.DESCRIPTION, "JeevaMitra Appointment ${req.bookingCode}\nReason: ${req.message}\nClinic Phone: ${req.doctorPhone}")
                            }
                            try { ctx.startActivity(intent) } catch (_: Exception) {}
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add to Calendar", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (req.doctorPhone.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${req.doctorPhone}")))
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Call, null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Call", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (req.status == CareRequestStatus.PENDING) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onStatusChange(CareRequestStatus.CONFIRMED) }) { Text("Mark Confirmed") }
                    TextButton(onClick = { onStatusChange(CareRequestStatus.DECLINED) }) { Text("Mark Declined") }
                }
            }
        }
    }
}

@Composable
private fun AppointmentConfirmationDialog(
    request: CareRequest,
    patientName: String,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(AlertYellow.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏳", fontSize = 28.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Request Sent to Doctor",
                    style = MaterialTheme.typography.titleLarge,
                    color = White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Awaiting Doctor Confirmation",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertYellow,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = CyanAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        request.bookingCode.ifBlank { "#GC-PENDING" },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    color = Navy800,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(request.doctorName, style = MaterialTheme.typography.titleSmall, color = White, fontWeight = FontWeight.Bold)
                        if (request.specialty.isNotBlank()) {
                            Text(request.specialty, style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                        }
                        Text(request.hospitalName, style = MaterialTheme.typography.bodySmall, color = Gray500)
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = CardBorder)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Date & Slot:", style = MaterialTheme.typography.labelSmall, color = Gray500)
                            Text(request.requestedDate, style = MaterialTheme.typography.labelSmall, color = White, fontWeight = FontWeight.SemiBold)
                        }
                        if (request.message.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Reason:", style = MaterialTheme.typography.labelSmall, color = Gray500)
                                Text(request.message, style = MaterialTheme.typography.labelSmall, color = White)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Action: Add to Calendar
                Button(
                    onClick = {
                        val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
                            data = CalendarContract.Events.CONTENT_URI
                            putExtra(CalendarContract.Events.TITLE, "Doctor Consultation: ${request.doctorName}")
                            putExtra(CalendarContract.Events.EVENT_LOCATION, request.hospitalName)
                            putExtra(
                                CalendarContract.Events.DESCRIPTION,
                                "JeevaMitra Appointment ${request.bookingCode} for $patientName.\nReason: ${request.message}\nClinic Phone: ${request.doctorPhone}"
                            )
                        }
                        try {
                            ctx.startActivity(calendarIntent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900)
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add to Google Calendar", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))

                if (request.doctorPhone.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val text = "Hi, I have booked appointment ${request.bookingCode} for $patientName with ${request.doctorName} on ${request.requestedDate}."
                                WhatsAppSender.openChat(ctx, request.doctorPhone, text)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
                        ) {
                            Icon(Icons.Default.Send, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("WhatsApp", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${request.doctorPhone}")))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Call, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Call Clinic", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Done", color = Gray500)
                }
            }
        }
    }
}
