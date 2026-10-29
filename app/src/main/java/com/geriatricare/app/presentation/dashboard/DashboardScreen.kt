package com.geriatricare.app.presentation.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.geriatricare.app.data.bluetooth.BleDiscoveryInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.model.*
import com.geriatricare.app.presentation.dashboard.components.*
import com.geriatricare.app.ui.theme.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geriatricare.app.data.ml.FallStatus
import com.geriatricare.app.data.notification.NotificationTokenRegistrar
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    patientId : String,
    onBack    : () -> Unit,
    onCareTeam: () -> Unit = {},
    onEditPatient: () -> Unit = {},
    onStationMode: () -> Unit = {},
    viewModel : DashboardViewModel = hiltViewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val trendAnalysis   by viewModel.trendAnalysis.collectAsState()
    val connState       by viewModel.connectionState.collectAsState()
    val activeData      by viewModel.bleData.collectAsState()
    val scannedDevices  by viewModel.scannedDevices.collectAsState()
    val discoveryInfo   by viewModel.discoveryInfo.collectAsState()
    val scanMessage     by viewModel.scanMessage.collectAsState()
    val mlScore         by viewModel.mlRiskScore.collectAsState()
    val screeningSnapshot by viewModel.screeningSnapshot.collectAsState()
    val appointments    by viewModel.appointments.collectAsState()
    val todayMeds       by viewModel.todayMedications.collectAsState()
    val phoneLocation by viewModel.phoneLocation.collectAsState()
    val locationSharingState by viewModel.locationSharingState.collectAsState()

    // ── CCTV & Fall Detection States ──
    val fallStatus         by viewModel.fallStatus.collectAsState()
    val fallTimestamp      by viewModel.fallTimestamp.collectAsState()
    val fallAlertExactTime by viewModel.fallAlertExactTime.collectAsState()
    val personBoundingBox  by viewModel.personBoundingBox.collectAsState()
    val isCctvActive       by viewModel.isCctvActive.collectAsState()

    var showBleDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // ── Appointments & Single-Shot Booking Beep ──
    val confirmedAppts = remember(appointments) { appointments.filter { it.status == AppointmentStatus.CONFIRMED } }
    val pendingAppts = remember(appointments) { appointments.filter { it.status == AppointmentStatus.PENDING } }

    // Beeps strictly once per user app session when opening dashboard to view confirmed appointments
    var hasPlayedConfirmedBeep by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(confirmedAppts.isNotEmpty()) {
        if (confirmedAppts.isNotEmpty() && !hasPlayedConfirmedBeep) {
            hasPlayedConfirmedBeep = true
            com.geriatricare.app.data.alert.AlertSoundPlayer.playMessageBeep(context)
        }
    }

    // ── CCTV Fall Timer Tracker ──
    var fallElapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(fallStatus, fallTimestamp) {
        if (fallStatus != FallStatus.NORMAL && fallTimestamp > 0L) {
            while (true) {
                fallElapsedSeconds = ((System.currentTimeMillis() - fallTimestamp) / 1000L).coerceAtLeast(0L)
                kotlinx.coroutines.delay(500L)
            }
        } else {
            fallElapsedSeconds = 0L
        }
    }

    // ── Camera Permission for Real CCTV Feed ──
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted }

    // Android 13+ blocks all alert banners until the recipient explicitly grants
    // this permission. Register the current device on every dashboard entry too,
    // so an existing signed-in family account is not missed after an app update.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        NotificationTokenRegistrar.registerCurrentDevice()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var reminderToDelete by remember { mutableStateOf<MedicationReminder?>(null) }
    var appointmentToDelete by remember { mutableStateOf<Appointment?>(null) }

    // ── Runtime Bluetooth permissions ────────────────────────────────────────
    val requiredBlePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScan()
        } else {
            viewModel.reportBlePermissionsDenied()
        }
    }
    fun startScanWithPermissionCheck() {
        val allGranted = requiredBlePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.startScan() else blePermissionLauncher.launch(requiredBlePermissions)
    }

    // Opening the Bluetooth icon now immediately starts discovery after permissions
    // have been granted, rather than requiring a second tap on "Scan for Devices".
    LaunchedEffect(showBleDialog) {
        if (showBleDialog) startScanWithPermissionCheck()
    }

    val patient = uiState.patient

    Scaffold(
        containerColor = ClinicalPageBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = ClinicalText)
                    }
                },
                title = {
                    Column {
                        Text("JeevaMitra",
                            style = MaterialTheme.typography.titleLarge, color = ClinicalText, fontWeight = FontWeight.Bold)
                        Text("Wear Your Wellness.",
                            style = MaterialTheme.typography.labelSmall, color = ClinicalTeal)
                    }
                },
                actions = {
                    IconButton(onClick = onStationMode) {
                        Icon(Icons.Default.CameraAlt, "Station Mode", tint = ClinicalText)
                    }
                    IconButton(onClick = onEditPatient) {
                        Icon(Icons.Default.Edit, "Edit Patient", tint = ClinicalText)
                    }
                    IconButton(onClick = { showBleDialog = true }) {
                        Icon(
                            Icons.Default.Bluetooth,
                            "BLE",
                            tint = if (connState.isConnected) ClinicalTeal else ClinicalSubtext
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClinicalCard)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding).background(ClinicalPageBackground),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { DashboardWelcomeHeader(patient?.name) }

            // ── Fall Emergency Alert Banner (With Exact Timing) ─────────────
            if (fallStatus != FallStatus.NORMAL || fallAlertExactTime != null) {
                item {
                    FallEmergencyBanner(
                        exactTime = fallAlertExactTime ?: SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date()),
                        elapsedSeconds = fallElapsedSeconds,
                        patientName = patient?.name ?: "Patient",
                        emergencyPhone = patient?.emergencyPhone ?: "",
                        onCallEmergency = {
                            val phone = patient?.emergencyPhone?.takeIf { it.isNotBlank() } ?: "108"
                            val dialIntent = android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                android.net.Uri.parse("tel:$phone")
                            )
                            try { context.startActivity(dialIntent) } catch (_: Exception) {}
                        },
                        onDismiss = {
                            viewModel.dismissFallAlert()
                        }
                    )
                }
            }

            // ── Connection Status Indicator ──────────────────────────────────
            item {
                ConnectionIndicator(connState, onScan = { showBleDialog = true })
            }

            // ── Auto-report warm-up indicator ────────────────────────────────
            if (uiState.autoReportWarmupRemainingMs > 0L) {
                item { AutoReportWarmupBanner(uiState.autoReportWarmupRemainingMs) }
            }

            // ── Patient Header ───────────────────────────────────────────────
            patient?.let { p ->
                item { PatientHeaderCard(p, connState.isConnected) }
            }

            item {
                ClinicalQuickActions(
                    onProfile = onEditPatient,
                    onCareTeam = onCareTeam,
                    onSensors = { showBleDialog = true },
                    onMonitoring = onStationMode
                )
            }

            // ── Alert Banner ─────────────────────────────────────────────────
            if (uiState.alertMessage.isNotEmpty()) {
                item { AlertBanner(uiState.alertMessage) }
            }

            // ── Emergency Section (visible when ML risk >= 50%) ──────────────
            if (mlScore != null && mlScore!! >= 0.50f) {
                item {
                    EmergencySection(
                        patient = patient,
                        riskScore = mlScore!!,
                        onCallEmergency = {
                            val phone = patient?.emergencyPhone?.takeIf { it.isNotBlank() }
                            if (phone != null) {
                                val dialIntent = android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    android.net.Uri.parse("tel:$phone")
                                )
                                context.startActivity(dialIntent)
                            }
                        },
                        onNotifyCaretaker = {
                            if (patient != null && patient.emergencyPhone.isNotBlank()) {
                                val msg = "⚠️ EMERGENCY: ${patient.name}'s health risk is critical (${((mlScore ?: 0f) * 100).toInt()}%). Immediate attention needed."
                                com.geriatricare.app.data.report.WhatsAppSender.openChat(context, patient.emergencyPhone, msg)
                            }
                        },
                        onBookEmergency = onCareTeam
                    )
                }
            }

            // ── Real Home CCTV Fall Detection Camera Tile ─────────────────────
            item {
                HomeCctvMonitorCard(
                    hasCameraPermission = hasCameraPermission,
                    isCctvActive = isCctvActive,
                    fallStatus = fallStatus,
                    boundingBox = personBoundingBox,
                    onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onToggleCctv = { viewModel.toggleCctv() },
                    onOpenFullscreen = onStationMode,
                    onProcessFrame = { proxy -> viewModel.processCctvFrame(proxy) }
                )
            }

            // ── ECG Waveform & Rhythm Report ─────────────────────────────────
            item {
                EcgLiveSection(
                    viewModel   = viewModel,
                    isConnected = connState.isConnected
                )
            }

            // ── 7-Day Early Deterioration Analysis ───────────────────────────
            item {
                var showTrendDialog by remember { mutableStateOf(false) }
                val risk = trendAnalysis.riskLevel
                val (riskBadgeColor, riskBadgeText) = when (risk) {
                    com.geriatricare.app.data.analytics.RiskLevel.HIGH -> AlertRed to "High Risk"
                    com.geriatricare.app.data.analytics.RiskLevel.MODERATE -> AlertYellow to "Moderate Risk"
                    com.geriatricare.app.data.analytics.RiskLevel.LOW -> EcgGreen to "Low Risk"
                    com.geriatricare.app.data.analytics.RiskLevel.INSUFFICIENT_DATA -> CyanAccent to "7-Day Analytics"
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = ClinicalCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
                    tonalElevation = 2.dp,
                    onClick = { showTrendDialog = true }
                ) {
                    Column(
                        Modifier
                            .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                            .padding(18.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(CyanAccent.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📈", fontSize = 22.sp)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text("Last 7-Day Analysis", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.Bold)
                                    Text("Continuous statistical early deterioration trend", style = MaterialTheme.typography.labelSmall, color = ClinicalSubtext)
                                }
                            }
                            Surface(
                                color = riskBadgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, riskBadgeColor.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    riskBadgeText,
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = riskBadgeColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CyanAccent)
                        }

                        // ── 4 Metrics Overview Row on Home Dashboard ──
                        Spacer(Modifier.height(14.dp))
                        val latestDay = trendAnalysis.dailyBreakdown.lastOrNull()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // HR
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ClinicalCard.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                            ) {
                                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("HR", style = MaterialTheme.typography.labelSmall, color = HeartRed, fontWeight = FontWeight.Bold)
                                    Text(
                                        latestDay?.avgHr?.let { "%.0f".format(it) } ?: "72",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClinicalText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("bpm", fontSize = 9.sp, color = Gray400)
                                }
                            }
                            // SpO2
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ClinicalCard.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                            ) {
                                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("SpO₂", style = MaterialTheme.typography.labelSmall, color = SpO2Purple, fontWeight = FontWeight.Bold)
                                    Text(
                                        latestDay?.avgSpo2?.let { "%.0f%%".format(it) } ?: "98%",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClinicalText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("Normal", fontSize = 9.sp, color = Gray400)
                                }
                            }
                            // Temp
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ClinicalCard.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                            ) {
                                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Temp", style = MaterialTheme.typography.labelSmall, color = TempOrange, fontWeight = FontWeight.Bold)
                                    Text(
                                        latestDay?.avgTemp?.let { "%.1f°".format(it) } ?: "36.5°",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ClinicalText,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("Celsius", fontSize = 9.sp, color = Gray400)
                                }
                            }
                            // ECG
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = ClinicalCard.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                            ) {
                                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ECG", style = MaterialTheme.typography.labelSmall, color = EcgGreen, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Normal",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = EcgGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        latestDay?.avgEcgBpm?.let { "%.0f bpm".format(it) } ?: "72 bpm",
                                        fontSize = 9.sp,
                                        color = Gray400
                                    )
                                }
                            }
                        }
                    }
                }

                if (showTrendDialog) {
                    Dialog(onDismissRequest = { showTrendDialog = false }) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = CardBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val riskColor = when (risk) {
                                com.geriatricare.app.data.analytics.RiskLevel.HIGH -> AlertRed
                                com.geriatricare.app.data.analytics.RiskLevel.MODERATE -> AlertYellow
                                com.geriatricare.app.data.analytics.RiskLevel.LOW -> EcgGreen
                                com.geriatricare.app.data.analytics.RiskLevel.INSUFFICIENT_DATA -> Gray500
                            }
                            val riskLabel = when (risk) {
                                com.geriatricare.app.data.analytics.RiskLevel.HIGH -> "High"
                                com.geriatricare.app.data.analytics.RiskLevel.MODERATE -> "Moderate"
                                com.geriatricare.app.data.analytics.RiskLevel.LOW -> "Low"
                                com.geriatricare.app.data.analytics.RiskLevel.INSUFFICIENT_DATA -> "Not enough data"
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 580.dp)
                                    .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                                    .padding(20.dp)
                            ) {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("7-Day Deterioration Analysis", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { showTrendDialog = false }) {
                                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Gray500)
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, riskColor.copy(alpha = 0.35f))) {
                                        Text(
                                            "Current Risk Status: $riskLabel",
                                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = riskColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(14.dp))
                                }

                                if (risk == com.geriatricare.app.data.analytics.RiskLevel.INSUFFICIENT_DATA) {
                                    item {
                                        Surface(
                                            color = Navy800.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(12.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle)
                                        ) {
                                            Column(Modifier.padding(14.dp)) {
                                                Text(
                                                    "Historical baseline data has been seeded. This analysis compares today against the patient's personalized baseline across 7 days.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Gray300
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    "Connect the sensor to begin collecting real vital readings for accurate trend analysis.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = CyanAccent,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    item {
                                        Text(trendAnalysis.summary, style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.9f))
                                        Spacer(Modifier.height(14.dp))
                                        Text("Per-vital Baseline & Slope", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    items(trendAnalysis.vitalTrends) { t ->
                                        Surface(
                                            color = Navy800.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (t.isConcerning) AlertYellow.copy(0.4f) else GlassBorderSubtle),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    if (t.isConcerning) "⚠" else "✓",
                                                    color = if (t.isConcerning) AlertYellow else EcgGreen,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    modifier = Modifier.padding(end = 10.dp)
                                                )
                                                Text(t.explanation, style = MaterialTheme.typography.labelSmall, color = Gray300, modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    item {
                                        Spacer(Modifier.height(14.dp))
                                        HorizontalDivider(color = Navy600)
                                        Spacer(Modifier.height(10.dp))
                                        Text("Daily Mean Averages (Saved History)", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                            Text("Date", style = MaterialTheme.typography.labelSmall, color = CyanAccent, modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold)
                                            Text("Temp", style = MaterialTheme.typography.labelSmall, color = CyanAccent, modifier = Modifier.weight(0.7f), fontWeight = FontWeight.Bold)
                                            Text("SpO₂", style = MaterialTheme.typography.labelSmall, color = CyanAccent, modifier = Modifier.weight(0.7f), fontWeight = FontWeight.Bold)
                                            Text("HR", style = MaterialTheme.typography.labelSmall, color = CyanAccent, modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold)
                                            Text("ECG", style = MaterialTheme.typography.labelSmall, color = CyanAccent, modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    items(trendAnalysis.dailyBreakdown) { day ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                                .background(Navy800.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(day.dateLabel, style = MaterialTheme.typography.labelSmall, color = White, modifier = Modifier.weight(1.2f))
                                            Text(day.avgTemp?.let { "%.1f°".format(it) } ?: "—", style = MaterialTheme.typography.labelSmall, color = Gray300, modifier = Modifier.weight(0.7f))
                                            Text(day.avgSpo2?.let { "%.0f%%".format(it) } ?: "—", style = MaterialTheme.typography.labelSmall, color = Gray300, modifier = Modifier.weight(0.7f))
                                            Text(day.avgHr?.let { "%.0f".format(it) } ?: "—", style = MaterialTheme.typography.labelSmall, color = Gray300, modifier = Modifier.weight(0.6f))
                                            Text(day.avgEcgBpm?.let { "%.0f".format(it) } ?: "—", style = MaterialTheme.typography.labelSmall, color = Gray300, modifier = Modifier.weight(0.6f))
                                        }
                                    }
                                }

                                item {
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Personalized statistical z-score and linear regression over patient history.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Gray500
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Local, data-derived health insight ─────────────────────────────
            if (connState.isConnected && (activeData.temp > 0 || activeData.hr > 0)) {
                item {
                    val ecgForInsight by viewModel.ecgAnalysis.collectAsState()
                    val onDeviceText = remember(activeData, ecgForInsight) {
                        com.geriatricare.app.data.insight.HealthInsightGenerator.generate(activeData, ecgForInsight)
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            Modifier
                                .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                                .padding(18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(CyanAccent.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🩺", fontSize = 20.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "AI Health Insight",
                                    style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(onDeviceText, style = MaterialTheme.typography.bodySmall, color = Gray500)

                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Generated from current sensor data; not a medical diagnosis.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray700,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ── SpO2 + Heart Rate row ────────────────────────────────────────
            item {
                val isRealSpo2 = connState.isConnected && activeData.spo2 > 0
                val displaySpo2 = if (isRealSpo2) "${activeData.spo2.toInt()}" else "--"
                val spo2Level = if (isRealSpo2) activeData.spo2.spO2Level() else VitalLevel.NORMAL
                val spo2Progress = if (isRealSpo2) (activeData.spo2 / 100f).coerceIn(0f, 1f) else 0f

                val isRealHr = connState.isConnected && activeData.hr > 0
                val displayHr = if (isRealHr) "${activeData.hr.toInt()}" else "--"
                val hrLevel = if (isRealHr) activeData.hr.heartRateLevel() else VitalLevel.NORMAL
                val hrProgress = if (isRealHr) (activeData.hr / 150f).coerceIn(0f, 1f) else 0f

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VitalCard(
                        title      = "SpO₂",
                        value      = displaySpo2,
                        unit       = "%",
                        icon       = Icons.Default.WaterDrop,
                        level      = spo2Level,
                        progress   = spo2Progress,
                        accentColor = SpO2Purple,
                        modifier   = Modifier.weight(1f)
                    )
                    VitalCard(
                        title      = "Heart Rate",
                        value      = displayHr,
                        unit       = "bpm",
                        icon       = Icons.Default.Favorite,
                        level      = hrLevel,
                        progress   = hrProgress,
                        accentColor = HeartRed,
                        modifier   = Modifier.weight(1f)
                    )
                }
            }

            // ── Temperature ──────────────────────────────────────────────────
            item {
                val isRealTemp = connState.isConnected && activeData.temp > 0
                val displayTemp = if (isRealTemp) activeData.temp else 0f
                val tempLevel = if (isRealTemp) activeData.temp.temperatureLevel() else VitalLevel.NORMAL

                TemperatureCard(
                    celsius = displayTemp,
                    level   = tempLevel
                )
            }

            // Phone location is independent of the ESP32 and live vital readings.
            item {
                PhoneLocationPanel(
                    patientId = patientId,
                    location = phoneLocation,
                    sharing = locationSharingState,
                    onStartSharing = viewModel::startPhoneLocationSharing,
                    onStopSharing = viewModel::stopPhoneLocationSharing
                )
            }

            // ── ML risk score ────────────────────────────────────────────────
            item {
                var showMlRiskDialog by remember { mutableStateOf(false) }

                MlRiskCard(
                    snapshot = screeningSnapshot,
                    onClick = { showMlRiskDialog = true }
                )

                if (showMlRiskDialog) {
                    MlRiskDialog(
                        snapshot  = screeningSnapshot,
                        onDismiss = { showMlRiskDialog = false }
                    )
                }
            }

            // ── Appointments (Confirmed vs Pending Doctor Confirmation) ───────
            // 1. Confirmed Doctor Appointments (Prominently shows Confirmed Doctor Timing)
            if (confirmedAppts.isNotEmpty()) {
                item {
                    val ctx = LocalContext.current
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, EcgGreen.copy(alpha = 0.6f)),
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            Modifier
                                .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                                .padding(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(38.dp).background(EcgGreen.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Text("✅", fontSize = 20.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Booking Confirmed!", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold)
                                        Text("Doctor approved your visit", style = MaterialTheme.typography.labelSmall, color = EcgGreen)
                                    }
                                }
                                Surface(color = EcgGreen.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        "CONFIRMED",
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EcgGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))

                            confirmedAppts.forEach { appt ->
                                Surface(
                                    color = Navy800.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, EcgGreen.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(appt.doctorName, style = MaterialTheme.typography.titleSmall, color = White, fontWeight = FontWeight.Bold)
                                                Text(appt.hospitalName, style = MaterialTheme.typography.labelSmall, color = Gray500)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (appt.bookingCode.isNotBlank()) {
                                                    Surface(color = CyanAccent.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                                        Text(
                                                            appt.bookingCode,
                                                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = CyanAccent,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(Modifier.width(6.dp))
                                                }
                                                IconButton(
                                                    onClick = { appointmentToDelete = appt },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Cancel Appointment",
                                                        tint = AlertRed.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        // Available Doctor Timing Box
                                        Surface(
                                            color = EcgGreen.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, EcgGreen.copy(alpha = 0.25f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.AccessTime, contentDescription = null, tint = EcgGreen, modifier = Modifier.size(20.dp))
                                                Spacer(Modifier.width(10.dp))
                                                Column {
                                                    Text("Confirmed Doctor Timing", style = MaterialTheme.typography.labelSmall, color = EcgGreen, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        "${appt.date} • ${appt.timeSlot}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = White,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        if (appt.reason.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text("Reason: ${appt.reason}", style = MaterialTheme.typography.bodySmall, color = Gray300)
                                        }

                                        Spacer(Modifier.height(10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    val intent = android.content.Intent(android.provider.CalendarContract.Events.CONTENT_URI.toString()).apply {
                                                        action = android.content.Intent.ACTION_INSERT
                                                        data = android.provider.CalendarContract.Events.CONTENT_URI
                                                        putExtra(android.provider.CalendarContract.Events.TITLE, "Doctor Visit: ${appt.doctorName}")
                                                        putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, appt.hospitalName)
                                                        putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "Appointment ${appt.bookingCode} with ${appt.doctorName} at ${appt.timeSlot}")
                                                    }
                                                    try { ctx.startActivity(intent) } catch (_: Exception) {}
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.DateRange, null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Calendar", style = MaterialTheme.typography.labelSmall)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val phoneIntent = android.content.Intent(
                                                        android.content.Intent.ACTION_DIAL,
                                                        android.net.Uri.parse("tel:04712444270")
                                                    )
                                                    try { ctx.startActivity(phoneIntent) } catch (_: Exception) {}
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Call, null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Call Hospital", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Pending Requests Awaiting Doctor Confirmation
            if (pendingAppts.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AlertYellow.copy(alpha = 0.5f)),
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            Modifier
                                .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                                .padding(18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(38.dp).background(AlertYellow.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Text("⏳", fontSize = 20.sp) }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Appointment Requested", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold)
                                    Text("Awaiting confirmation by doctor", style = MaterialTheme.typography.labelSmall, color = AlertYellow)
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            pendingAppts.forEach { appt ->
                                Surface(
                                    color = Navy800.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(appt.doctorName, style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
                                            Text("Requested Slot: ${appt.date} • ${appt.timeSlot}", style = MaterialTheme.typography.labelSmall, color = Gray300)
                                            Text(appt.hospitalName, style = MaterialTheme.typography.labelSmall, color = Gray500)
                                        }
                                        Surface(color = AlertYellow.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                            Text(
                                                "PENDING",
                                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AlertYellow,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { appointmentToDelete = appt },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Cancel Request",
                                                tint = AlertRed.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Today's Medications ──────────────────────────────────────────
            if (todayMeds.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            Modifier
                                .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                                .padding(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(38.dp).background(CyanAccent.copy(alpha = 0.12f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Text("💊", fontSize = 20.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Today's Medications", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold)
                                        Text("${todayMeds.size} scheduled today", style = MaterialTheme.typography.labelSmall, color = Gray400)
                                    }
                                }
                                Surface(
                                    color = CyanAccent.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "DAILY RX",
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            todayMeds.forEach { med ->
                                val (timeSlot, foodRelation, mealIcon) = med.resolveTimingInstruction()
                                val isBeforeFood = foodRelation.contains("before", ignoreCase = true)
                                val timingBadgeColor = if (isBeforeFood) AlertYellow else EcgGreen

                                Surface(
                                    color = Navy800.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(med.medicationName, style = MaterialTheme.typography.labelLarge, color = White, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Surface(color = CyanAccent.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                                        Text(med.dosage, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = CyanAccent, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = Gray300, modifier = Modifier.size(13.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(med.scheduledTime, style = MaterialTheme.typography.labelSmall, color = Gray300, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                when (med.status) {
                                                    ReminderStatus.TAKEN -> {
                                                        Surface(color = EcgGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                                            Text("TAKEN", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = EcgGreen, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    ReminderStatus.SKIPPED -> {
                                                        Surface(color = AlertYellow.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                                            Text("SKIPPED", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = AlertYellow, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    ReminderStatus.PENDING -> {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            FilledTonalButton(
                                                                onClick = { viewModel.markMedicationTaken(med.id) },
                                                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = EcgGreen.copy(alpha = 0.2f)),
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                            ) { Text("Taken", style = MaterialTheme.typography.labelSmall, color = EcgGreen) }
                                                            FilledTonalButton(
                                                                onClick = { viewModel.markMedicationSkipped(med.id) },
                                                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = AlertYellow.copy(alpha = 0.2f)),
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                            ) { Text("Skip", style = MaterialTheme.typography.labelSmall, color = AlertYellow) }
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.width(4.dp))
                                                IconButton(
                                                    onClick = { reminderToDelete = med },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Delete Reminder",
                                                        tint = AlertRed.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        // Prominent Meal Timing & Before/After Food Badge
                                        Surface(
                                            color = timingBadgeColor.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, timingBadgeColor.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(mealIcon, fontSize = 14.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "$timeSlot: $foodRelation",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = timingBadgeColor,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        if (med.instructions.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "Doctor Note: ${med.instructions}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Gray500
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Report & Contact Actions ──────────────────────────────────────
            item {
                val ctx = LocalContext.current
                val ecgAnalysisForReport by viewModel.ecgAnalysis.collectAsState()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = CardBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        Modifier
                            .background(Brush.verticalGradient(listOf(CardGradientStart, CardGradientEnd)))
                            .padding(18.dp)
                    ) {
                        Text("Report & Contact", style = MaterialTheme.typography.titleMedium, color = White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))

                        ActionTile(
                            title       = "Send Report",
                            subtitle    = "Instantly share the PDF with the bystander on WhatsApp",
                            icon        = Icons.Default.Send,
                            accentColor = Color(0xFF25D366),
                            enabled     = !patient?.emergencyPhone.isNullOrBlank(),
                            onClick     = {
                                val file = com.geriatricare.app.data.report.ReportGenerator.generate(
                                    context = ctx,
                                    patient = patient,
                                    vitals  = activeData,
                                    ecg     = ecgAnalysisForReport
                                )
                                com.geriatricare.app.data.report.WhatsAppSender.sendReportToBystander(
                                    context         = ctx,
                                    bystanderName   = patient?.emergencyContact.orEmpty(),
                                    bystanderPhone  = patient?.emergencyPhone.orEmpty(),
                                    patientName     = patient?.name.orEmpty(),
                                    reportFile      = file
                                )
                            }
                        )
                        if (patient?.emergencyPhone.isNullOrBlank()) {
                            Text(
                                "Add the bystander's phone number in Edit Patient to enable this.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray500,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        ActionTile(
                            title       = "Book Appointment",
                            subtitle    = "Find a nearby doctor in Trivandrum and book a slot",
                            icon        = Icons.Default.LocalHospital,
                            accentColor = CyanAccent,
                            enabled     = true,
                            onClick     = onCareTeam
                        )

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Navy600)
                        Spacer(Modifier.height(10.dp))

                        // ── Scheduled scans ───────────────
                        val workInfos by com.geriatricare.app.data.scan.ScanScheduler
                            .isScheduledFlow(ctx, patientId)
                            .collectAsState(initial = emptyList())
                        val scheduledOn = workInfos.any {
                            it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.RUNNING
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Scheduled Scans", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "8:00 AM & 6:00 PM · ~2.5 min each · report auto-sent every time",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Gray500
                                )
                            }
                            Switch(
                                checked = scheduledOn,
                                onCheckedChange = { turnOn ->
                                    if (turnOn) {
                                        com.geriatricare.app.data.scan.ScanScheduler.scheduleNext(
                                            ctx, patientId, patient?.bleDeviceAddress.orEmpty()
                                        )
                                    } else {
                                        com.geriatricare.app.data.scan.ScanScheduler.cancel(ctx, patientId)
                                    }
                                },
                                enabled = !patient?.bleDeviceAddress.isNullOrBlank()
                            )
                        }
                        if (patient?.bleDeviceAddress.isNullOrBlank()) {
                            Text(
                                "Pair an ESP32 device first (Scan for Devices above) to enable scheduled scans.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray500,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Navy600)
                        Spacer(Modifier.height(10.dp))

                        Text("More options", style = MaterialTheme.typography.labelSmall, color = Gray500, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val file = com.geriatricare.app.data.report.ReportGenerator.generate(
                                        context = ctx,
                                        patient = patient,
                                        vitals  = activeData,
                                        ecg     = ecgAnalysisForReport
                                    )
                                    val uri = com.geriatricare.app.data.report.ReportGenerator.shareUri(ctx, file)
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(shareIntent, "Share health report"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share via…")
                            }
                            OutlinedButton(
                                onClick = {
                                    val phone = patient?.emergencyPhone?.takeIf { it.isNotBlank() }
                                    if (phone != null) {
                                        val dialIntent = android.content.Intent(
                                            android.content.Intent.ACTION_DIAL,
                                            android.net.Uri.parse("tel:$phone")
                                        )
                                        ctx.startActivity(dialIntent)
                                    }
                                },
                                enabled = !patient?.emergencyPhone.isNullOrBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Call Contact")
                            }
                        }
                    }
                }
            }

            // ── Last Saved indicator ─────────────────────────────────────────
            if (uiState.lastSavedTime > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = EcgGreen, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Last saved: ${formatTime(uiState.lastSavedTime)}",
                            style = MaterialTheme.typography.labelSmall, color = Gray500)
                    }
                }
            }
        }
    }

    // ── BLE Device Dialog ────────────────────────────────────────────────────
    if (showBleDialog) {
        BleDeviceDialog(
            devices       = scannedDevices,
            discoveryInfo = discoveryInfo,
            isScanning    = connState.isScanning,
            isConnected   = connState.isConnected,
            connectedName = connState.deviceName,
            scanMessage   = scanMessage,
            onScan        = { startScanWithPermissionCheck() },
            onConnect     = { viewModel.connect(it) },
            onDisconnect  = { viewModel.disconnect() },
            onDismiss     = { showBleDialog = false }
        )
    }

    // ── Delete Medication Reminder Dialog ────────────────────────────────────
    reminderToDelete?.let { rem ->
        AlertDialog(
            onDismissRequest = { reminderToDelete = null },
            containerColor = Navy800,
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = AlertRed) },
            title = { Text("Delete Reminder?", color = White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to remove the reminder for ${rem.medicationName} (${rem.dosage}) scheduled at ${rem.scheduledTime}?",
                    color = Gray300,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteMedicationReminder(rem.id)
                        reminderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Delete", color = White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { reminderToDelete = null }) {
                    Text("Cancel", color = Gray300)
                }
            }
        )
    }

    // ── Cancel/Delete Appointment Dialog ─────────────────────────────────────
    appointmentToDelete?.let { appt ->
        val isConfirmed = appt.status == AppointmentStatus.CONFIRMED
        AlertDialog(
            onDismissRequest = { appointmentToDelete = null },
            containerColor = Navy800,
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = AlertRed) },
            title = {
                Text(
                    if (isConfirmed) "Cancel Appointment?" else "Cancel Booking Request?",
                    color = White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you sure you want to cancel the appointment with ${appt.doctorName} at ${appt.hospitalName} scheduled for ${appt.date} (${appt.timeSlot})?",
                    color = Gray300,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAppointment(appt.id)
                        appointmentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Cancel Booking", color = White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { appointmentToDelete = null }) {
                    Text("Keep", color = Gray300)
                }
            }
        )
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun DashboardWelcomeHeader(patientName: String?) {
    val firstName = patientName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "there"
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val date = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = ClinicalCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).background(ClinicalTeal.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = ClinicalTeal)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$greeting, $firstName!",
                    style = MaterialTheme.typography.titleLarge,
                    color = ClinicalText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your live wellness overview",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClinicalSubtext
                )
            }
            Text(date, style = MaterialTheme.typography.labelSmall, color = ClinicalBlue, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ClinicalQuickActions(
    onProfile: () -> Unit,
    onCareTeam: () -> Unit,
    onSensors: () -> Unit,
    onMonitoring: () -> Unit
) {
    val actions = listOf(
        Triple("Profile", Icons.Default.Person, onProfile),
        Triple("Care team", Icons.Default.LocalHospital, onCareTeam),
        Triple("Sensors", Icons.Default.Bluetooth, onSensors),
        Triple("Monitor", Icons.Default.MonitorHeart, onMonitoring)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = ClinicalCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, icon, action) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = ClinicalPaleBlue,
                        onClick = action
                    ) {
                        Column(
                            Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(icon, contentDescription = label, tint = ClinicalBlue, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = ClinicalText, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Connection Status Indicator — 🟢 LIVE / 🟠 WAITING / 🔴 DISCONNECTED
 */
@Composable
private fun ConnectionIndicator(state: BleConnectionState, onScan: () -> Unit) {
    val (statusColor, statusIcon, statusText, statusDetail) = when (state.status) {
        ConnectionStatus.CONNECTED -> {
            arrayOf(EcgGreen, Icons.Default.BluetoothConnected, "LIVE", "Connected — ${state.deviceName}")
        }
        ConnectionStatus.SCANNING, ConnectionStatus.CONNECTING -> {
            arrayOf(AlertYellow, Icons.Default.Bluetooth, "WAITING", if (state.status == ConnectionStatus.SCANNING) "Scanning for devices…" else "Connecting to ${state.deviceName}…")
        }
        ConnectionStatus.DISCONNECTED -> {
            arrayOf(AlertRed, Icons.Default.BluetoothDisabled, "DISCONNECTED", "Sensor not connected")
        }
    }
    val dotColor = statusColor as Color
    val icon = statusIcon as androidx.compose.ui.graphics.vector.ImageVector
    val label = statusText as String
    val detail = statusDetail as String

    val pulseAlpha by rememberInfiniteTransition(label = "connPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connPulseAlpha"
    )

    Surface(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(14.dp),
        color     = ClinicalCard,
        border    = androidx.compose.foundation.BorderStroke(1.dp, dotColor.copy(alpha = 0.24f)),
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = if (state.isConnected) pulseAlpha else 1f), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = dotColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.width(10.dp))
            Icon(icon, null, tint = dotColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                if (!state.isConnected) {
                    Text("Tap Bluetooth icon to scan & connect", style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
            }
            if (!state.isConnected && !state.isScanning) {
                TextButton(onClick = onScan) {
                    Text("Scan", color = CyanAccent, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PatientHeaderCard(patient: Patient, isLive: Boolean) {
    val initials = patient.name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    val livePulse by rememberInfiniteTransition(label = "pulseRing").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Surface(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(Brush.linearGradient(listOf(ClinicalBlue, ClinicalTeal)), CircleShape)
                    .border(2.dp, ClinicalTeal.copy(alpha = livePulse), CircleShape),
                Alignment.Center
            ) {
                Text(initials, style = MaterialTheme.typography.titleLarge, color = White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(patient.name, style = MaterialTheme.typography.titleLarge, color = ClinicalText, fontWeight = FontWeight.Bold)
                Text("${patient.age} yrs  •  ${patient.gender}  •  ${patient.bloodGroup}",
                    style = MaterialTheme.typography.bodySmall, color = ClinicalSubtext)
                Spacer(Modifier.height(4.dp))
                Text(patient.condition.ifEmpty { "General Care" },
                    style = MaterialTheme.typography.labelSmall, color = ClinicalTeal, fontWeight = FontWeight.SemiBold)
                if (patient.roomNumber.isNotEmpty()) {
                    Text("Room ${patient.roomNumber}", style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
            }
            // Status badge — LIVE or HISTORICAL
            val badgeColor = if (isLive) EcgGreen else Color(0xFF448AFF)
            val badgeText = if (isLive) "LIVE" else "HISTORICAL"
            Surface(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(8.dp).background(badgeColor, CircleShape))
                    Spacer(Modifier.height(2.dp))
                    Text(badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Emergency section — visible when ML model detects high risk (>= 50%)
 */
@Composable
private fun EmergencySection(
    patient: Patient?,
    riskScore: Float,
    onCallEmergency: () -> Unit,
    onNotifyCaretaker: () -> Unit,
    onBookEmergency: () -> Unit
) {
    val pulseAlpha by rememberInfiniteTransition(label = "emergPulse").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emergAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = AlertRed.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(2.dp, AlertRed.copy(alpha = pulseAlpha)),
        tonalElevation = 6.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).background(AlertRed.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = AlertRed, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("EMERGENCY ALERT", style = MaterialTheme.typography.titleMedium, color = AlertRed, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("ML Risk Score: ${(riskScore * 100).toInt()}% — Immediate action recommended", style = MaterialTheme.typography.labelSmall, color = White)
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCallEmergency,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Call Emergency", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onNotifyCaretaker,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Message, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Notify Caretaker", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBookEmergency,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.LocalHospital, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Book Emergency Appointment", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AutoReportWarmupBanner(remainingMs: Long) {
    val remainingSec = (remainingMs / 1000).coerceAtLeast(0)
    val min = remainingSec / 60
    val sec = remainingSec % 60
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = CyanAccent.copy(0.10f),
        border   = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CyanAccent)
            Spacer(Modifier.width(10.dp))
            Text(
                "Stabilizing all 4 metrics — auto report in %d:%02d".format(min, sec),
                style = MaterialTheme.typography.labelMedium,
                color = CyanAccent,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AlertBanner(message: String) {
    val pulseAlpha by rememberInfiniteTransition(label = "alertPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alertAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = AlertRed.copy(0.14f),
        border   = androidx.compose.foundation.BorderStroke(1.dp, AlertRed.copy(alpha = pulseAlpha)),
        tonalElevation = 6.dp
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(AlertRed.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, null, tint = AlertRed, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "PHYSIOLOGICAL ALERT",
                    style = MaterialTheme.typography.labelSmall,
                    color = AlertRed,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BleDeviceDialog(
    devices      : List<BluetoothDevice>,
    discoveryInfo: Map<String, BleDiscoveryInfo>,
    isScanning   : Boolean,
    isConnected  : Boolean,
    connectedName: String,
    scanMessage  : String?,
    onScan       : () -> Unit,
    onConnect    : (BluetoothDevice) -> Unit,
    onDisconnect : () -> Unit,
    onDismiss    : () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Navy700) {
            Column(Modifier.padding(20.dp)) {
                Text("Bluetooth Devices", style = MaterialTheme.typography.titleLarge, color = White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Select GeriatriCare_ESP32C3 (or your original ESP32). Scan inside this app, not the phone's pairing screen.", style = MaterialTheme.typography.bodySmall, color = Gray500)

                if (isConnected) {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = EcgGreen.copy(0.10f), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BluetoothConnected, null, tint = EcgGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("Connected: $connectedName", color = EcgGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AlertRed)) {
                        Text("Disconnect")
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900),
                        enabled = !isScanning) {
                        if (isScanning) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Navy900, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isScanning) "Scanning…" else "Scan for Devices", fontWeight = FontWeight.Bold)
                    }

                    if (devices.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Found Devices", style = MaterialTheme.typography.labelMedium, color = Gray500)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(devices.sortedByDescending { discoveryInfo[it.address]?.isGeriatriCare == true }, key = { it.address }) { device ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnect(device); onDismiss() },
                                shape = RoundedCornerShape(10.dp),
                                color = Navy600
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bluetooth, null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(discoveryInfo[device.address]?.name ?: "Unnamed BLE device", color = White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        Text(device.address, color = Gray500, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        }
                    } else if (!isScanning) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            scanMessage ?: "No BLE devices found. Keep the ESP32 powered, within 2 metres, and ensure the GeriatriCare sketch is uploaded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (scanMessage == null) Gray500 else AlertYellow
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close", color = Gray500)
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    title      : String,
    subtitle   : String,
    icon       : androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    enabled    : Boolean,
    onClick    : () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Gray500)
            }
            Icon(Icons.Default.ChevronRight, null, tint = accentColor)
        }
    }
}

private fun formatTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}

@Composable
private fun EcgLiveSection(
    viewModel  : DashboardViewModel,
    isConnected: Boolean
) {
    val ecgPoints   by viewModel.bleManager.ecgDisplayBuffer.collectAsState()
    val ecgRate by viewModel.bleManager.ecgSampleRate.collectAsState()
    val ecgAnalysis by viewModel.ecgAnalysis.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        EcgChart(
            ecgPoints   = ecgPoints,
            isConnected = isConnected,
            analysis = ecgAnalysis,
            sampleRateHz = ecgRate,
            modifier    = Modifier.fillMaxWidth()
        )

        // Keep acquisition/contact status in the chart's compact badge, not a
        // second warning card underneath. Show a rhythm report only when usable.
        if (isConnected && com.geriatricare.app.data.model.canDisplayEcg(ecgPoints, ecgAnalysis) &&
            ecgAnalysis.estimatedBpm != null && ecgAnalysis.status in setOf(
                com.geriatricare.app.data.model.EcgStatus.NORMAL,
                com.geriatricare.app.data.model.EcgStatus.IRREGULAR
            )) {
            EcgReportCard(
                analysis = ecgAnalysis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Flashing Critical Fall Alert Banner displaying exact detection time
 */
@Composable
private fun FallEmergencyBanner(
    exactTime: String,
    elapsedSeconds: Long,
    patientName: String,
    emergencyPhone: String,
    onCallEmergency: () -> Unit,
    onDismiss: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fallAlertPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "fallPulseAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AlertRed.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(2.dp, AlertRed.copy(alpha = pulseAlpha)),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(AlertRed.copy(alpha = 0.25f), CardBackground)))
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(AlertRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "CRITICAL FALL DETECTED!",
                            style = MaterialTheme.typography.titleMedium,
                            color = AlertRed,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Exact Time: $exactTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    color = AlertRed,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    val mm = (elapsedSeconds / 60)
                    val ss = (elapsedSeconds % 60)
                    val timeStr = "%02d:%02d".format(mm, ss)
                    Text(
                        "-$timeStr",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Real-time CCTV AI detected a sudden patient fall. Emergency alerts and GPS location have been dispatched to family & caretakers.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray300,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCallEmergency,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (emergencyPhone.isNotBlank()) "Call Caretaker" else "Emergency Call",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Gray500),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Check, null, tint = White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("I'm OK / Dismiss", color = White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Real-time CCTV Room Camera monitor with live CameraX video feed,
 * live timestamp clock overlay, AI pose bounding box, and recording status.
 */
@Composable
private fun HomeCctvMonitorCard(
    hasCameraPermission: Boolean,
    isCctvActive: Boolean,
    fallStatus: FallStatus,
    boundingBox: FloatArray?,
    onRequestPermission: () -> Unit,
    onToggleCctv: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onProcessFrame: (androidx.camera.core.ImageProxy) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Live clock for CCTV overlay: updates every 1 second
    var liveClockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        while (true) {
            liveClockText = sdf.format(Date())
            kotlinx.coroutines.delay(1000L)
        }
    }

    // Blinking REC dot
    val recBlink by rememberInfiniteTransition(label = "recBlink").animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "recDotAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (fallStatus != FallStatus.NORMAL) AlertRed else GlassBorderSubtle
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // CCTV Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isCctvActive && hasCameraPermission) AlertRed.copy(alpha = recBlink) else Gray500)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "LIVE CCTV MONITOR",
                        style = MaterialTheme.typography.titleSmall,
                        color = White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = Navy800,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "CAM-01",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    color = when (fallStatus) {
                        FallStatus.NORMAL -> EcgGreen.copy(alpha = 0.15f)
                        else -> AlertRed.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (fallStatus == FallStatus.NORMAL) EcgGreen.copy(alpha = 0.4f) else AlertRed
                    )
                ) {
                    Text(
                        if (fallStatus == FallStatus.NORMAL) "● ACTIVE AI" else "⚠ FALL DETECTED",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fallStatus == FallStatus.NORMAL) EcgGreen else AlertRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Camera View Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                if (!hasCameraPermission) {
                    // Permission prompt inside CCTV monitor
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = AlertYellow,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "CCTV Camera Feed Offline",
                            color = White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Enable camera permission to activate real-time 24/7 CCTV fall detection.",
                            color = Gray400,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, null, tint = Navy900, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Activate CCTV Camera", color = Navy900, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else if (!isCctvActive) {
                    // CCTV Paused
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CameraAlt, null, tint = Gray500, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("CCTV Camera Standby", color = Gray400, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onToggleCctv,
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent)
                        ) {
                            Text("Resume CCTV Feed", color = CyanAccent, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    // LIVE CAMERAX PREVIEW
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            val analysisExecutor = Executors.newSingleThreadExecutor()

                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .setTargetResolution(android.util.Size(640, 480))
                                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                        .build().also {
                                            it.setAnalyzer(analysisExecutor) { proxy ->
                                                onProcessFrame(proxy)
                                            }
                                        }

                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("HomeCctv", "Failed to bind camera", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Bounding Box Canvas Overlay
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val box = boundingBox
                        if (box != null && box.size == 4) {
                            val left = box[0] * size.width
                            val top = box[1] * size.height
                            val right = box[2] * size.width
                            val bottom = box[3] * size.height

                            val boxColor = if (fallStatus == FallStatus.NORMAL) Color(0xFF25D366) else Color.Red
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 3.dp.toPx())
                            )

                            // Corner accents for CCTV look
                            val cornerLen = 14.dp.toPx()
                            val strokeW = 3.dp.toPx()
                            drawLine(boxColor, Offset(left, top), Offset(left + cornerLen, top), strokeW)
                            drawLine(boxColor, Offset(left, top), Offset(left, top + cornerLen), strokeW)
                            drawLine(boxColor, Offset(right, top), Offset(right - cornerLen, top), strokeW)
                            drawLine(boxColor, Offset(right, top), Offset(right, top + cornerLen), strokeW)
                            drawLine(boxColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                            drawLine(boxColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                            drawLine(boxColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                            drawLine(boxColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)
                        }
                    }

                    // Top CCTV OSD Overlay (On-Screen Display)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = recBlink))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "REC",
                                color = Color.Red,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            liveClockText,
                            color = White,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    // Bottom CCTV Controls Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                )
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (boundingBox != null) "● Human Detected" else "Searching for human…",
                            color = if (boundingBox != null) (if (fallStatus == FallStatus.NORMAL) EcgGreen else AlertRed) else Gray400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onToggleCctv,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, "Pause CCTV", tint = White, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = onOpenFullscreen,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ArrowForward, "Station Mode", tint = White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

        }
    }
}
