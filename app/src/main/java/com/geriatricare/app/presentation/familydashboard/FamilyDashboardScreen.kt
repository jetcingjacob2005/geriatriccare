package com.geriatricare.app.presentation.familydashboard

import android.graphics.BitmapFactory
import android.util.Base64
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.geriatricare.app.data.analytics.RiskLevel
import com.geriatricare.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyDashboardScreen(
    onBack    : () -> Unit,
    onCareTeam: () -> Unit,
    viewModel : FamilyDashboardViewModel = hiltViewModel()
) {
    val patient by viewModel.patient.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val trendAnalysis by viewModel.trendAnalysis.collectAsState()
    val latest = scanResults.firstOrNull()
    var showTrendDialog by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var locationNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            locationNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000L)
        }
    }

    val pulseAlpha by rememberInfiniteTransition(label = "familyAlertPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "familyPulse"
    )

    Scaffold(
        containerColor = ClinicalPageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("JeevaMitra", color = ClinicalText, fontWeight = FontWeight.Bold)
                        Text(patient?.name?.ifBlank { "Family dashboard" } ?: "Family dashboard", color = ClinicalSubtext, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = ClinicalText) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClinicalCard)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).background(ClinicalPageBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // A Firestore value alone is not proof that a fall is happening now.
            // It must be refreshed by the Station Mode camera within this short
            // window; old events remain in alert history rather than repeating as
            // a live emergency every time the family dashboard opens.
            val nowForFall = System.currentTimeMillis()
            val rawFallStatus = patient?.fallStatus
            val hasFreshFallHeartbeat = patient?.fallStatusObservedAt
                ?.let { nowForFall - it in 0..20_000L } ?: false
            val isActiveFall = (rawFallStatus == "FALL_DETECTED" || rawFallStatus == "UNCONSCIOUS") && hasFreshFallHeartbeat
            if (isActiveFall) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = AlertRed.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, AlertRed.copy(alpha = pulseAlpha)),
                        tonalElevation = 8.dp
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(36.dp).background(AlertRed.copy(0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = AlertRed, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        if (rawFallStatus == "UNCONSCIOUS") "CRITICAL: MOTIONLESS AFTER FALL — CHECK NOW" else "CRITICAL: PATIENT FALL DETECTED!",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("Station Mode camera flagged an emergency.", style = MaterialTheme.typography.labelSmall, color = AlertRed)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!patient?.emergencyPhone.isNullOrBlank()) {
                                    Button(
                                        onClick = {
                                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${patient?.emergencyPhone}")))
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = White)
                                    ) {
                                        Icon(Icons.Default.Call, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Call Bystander", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (!patient?.doctorPhone.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${patient?.doctorPhone}")))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.LocalHospital, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Call Hospital", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val now = System.currentTimeMillis()
                val lastScanAgeMs = latest?.let { now - it.timestamp } ?: Long.MAX_VALUE
                val isRecent = lastScanAgeMs < 5 * 60 * 1000L // within 5 minutes
                val isToday = lastScanAgeMs < 24 * 60 * 60 * 1000L

                val (badgeColor, statusTitle, statusSubtitle) = when {
                    isRecent -> Triple(StatusLive, "LIVE TELEMETRY", "Active stream synced just now")
                    isToday -> Triple(StatusHistorical, "HISTORICAL SYNC", "Last updated ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(latest!!.timestamp))}")
                    latest != null -> Triple(StatusWaiting, "STANDBY", "Last recorded on ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(latest.timestamp))}")
                    else -> Triple(StatusWaiting, "WAITING FOR FIRST SCAN", "Awaiting sensor connection and data stream")
                }

                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = badgeColor.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).background(badgeColor, CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(statusTitle, style = MaterialTheme.typography.labelSmall, color = badgeColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(statusSubtitle, style = MaterialTheme.typography.bodySmall, color = ClinicalText)
                        }
                    }
                }
            }

            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = ClinicalCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Gray500, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Read-only family view — updated live after each scan session.",
                            style = MaterialTheme.typography.labelSmall, color = Gray500
                        )
                    }
                }
            }

            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = ClinicalCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        Modifier
                            .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                            .padding(18.dp)
                    ) {
                        Text("Latest Scan", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                        if (latest == null) {
                            Spacer(Modifier.height(6.dp))
                            Text("No scans synced yet.", style = MaterialTheme.typography.bodySmall, color = Gray500)
                        } else {
                            Text(
                                SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(latest.timestamp)),
                                style = MaterialTheme.typography.labelSmall, color = Gray500
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                VitalMini("Temp", if (latest.temp > 0) "%.1f°C".format(latest.temp) else "—")
                                VitalMini("SpO2", if (latest.spo2 > 0) "${latest.spo2.toInt()}%" else "—")
                                VitalMini("HR", if (latest.hr > 0) "${latest.hr.toInt()} bpm" else "—")
                                VitalMini("ECG", latest.ecgBpm?.let { "$it bpm" } ?: latest.ecgStatus.replace('_', ' '))
                            }
                            if (latest.insightText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(latest.insightText, style = MaterialTheme.typography.bodySmall, color = Gray500)
                            }
                        }
                    }
                }
            }

            // Phone position is streamed through the patient document and does
            // not depend on whether any vital scan has been saved yet.
            item {
                val phone = patient?.phoneLocation?.takeIf { it.hasValidFix() }
                val observedAt = phone?.observedAt ?: 0L
                FamilyLocationMap(
                    lat = phone?.latitude ?: 0.0,
                    lon = phone?.longitude ?: 0.0,
                    hasFix = phone != null,
                    lastUpdatedLabel = if (observedAt > 0) SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(observedAt)) else "No position yet",
                    isLive = phone?.isLive(locationNow) == true,
                    statusLabel = when {
                        phone == null -> "Patient phone has not shared a position"
                        !phone.sharingActive -> "Phone sharing stopped · last known location"
                        !phone.locationAvailable -> "Phone location unavailable · last known position only"
                        !phone.isLive(locationNow) -> "No recent phone update · last known location"
                        else -> "Patient phone · recently updated"
                    },
                    accuracyMeters = phone?.accuracyMeters,
                    noFixMessage = "No location has been shared yet. On the phone carried by the patient, open their dashboard and select Share phone location."
                )
            }
            
            if (patient?.latestCameraFrame?.isNotBlank() == true) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderSubtle),
                        tonalElevation = 6.dp
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            val frameStr = patient?.latestCameraFrame
                            val imageBitmap = remember(frameStr) {
                                if (frameStr.isNullOrBlank()) null
                                else try {
                                    val bytes = Base64.decode(frameStr, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            if (imageBitmap != null) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "Live feed",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            // Status overlay
                            val status = if (isActiveFall) rawFallStatus ?: "NORMAL" else "NORMAL"
                            val statusColor: Color
                            val alertMsg: String
                            when (status) {
                                "FALL_DETECTED" -> { statusColor = AlertRed; alertMsg = "CRITICAL: FALL DETECTED!" }
                                "UNCONSCIOUS"  -> { statusColor = AlertRed; alertMsg = "CRITICAL: MOTIONLESS AFTER FALL — CHECK NOW" }
                                else           -> { statusColor = Color(0xFF25D366); alertMsg = "Live Room Monitor" }
                            }

                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(12.dp)
                                    .background(statusColor.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Videocam, null, tint = White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(alertMsg, color = White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    onClick = { showTrendDialog = true },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = ClinicalCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder), tonalElevation = 2.dp
                ) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📈", fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("Last 7-Day Analysis", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Gray500)
                    }
                }
            }

            item {
                Button(onClick = onCareTeam, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LocalHospital, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Care Team — Message / Book Appointment")
                }
            }

            item {
                Text("Recent Scan History", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
            }
            items(scanResults, key = { it.id }) { r ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ClinicalCard, border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(r.timestamp)),
                            style = MaterialTheme.typography.labelSmall, color = ClinicalText
                        )
                        Text(
                            listOfNotNull(
                                r.temp.takeIf { it > 0 }?.let { "%.1f°C".format(it) },
                                r.spo2.takeIf { it > 0 }?.let { "${it.toInt()}%" },
                                r.hr.takeIf { it > 0 }?.let { "${it.toInt()} bpm" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = Gray500
                        )
                    }
                }
            }
        }
    }

    if (showTrendDialog) {
        Dialog(onDismissRequest = { showTrendDialog = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = ClinicalCard, border = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder), modifier = Modifier.fillMaxWidth()) {
                val risk = trendAnalysis.riskLevel
                val riskColor = when (risk) {
                    RiskLevel.HIGH -> AlertRed
                    RiskLevel.MODERATE -> AlertYellow
                    RiskLevel.LOW -> EcgGreen
                    RiskLevel.INSUFFICIENT_DATA -> Gray500
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(20.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("7-Day Analysis", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showTrendDialog = false }) { Icon(Icons.Default.Close, null, tint = Gray500) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Surface(color = riskColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                            Text(
                                "Current Risk: ${risk.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium, color = riskColor, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(trendAnalysis.summary, style = MaterialTheme.typography.bodySmall, color = Gray500)
                        Spacer(Modifier.height(12.dp))
                    }
                    items(trendAnalysis.vitalTrends) { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.isConcerning) "⚠" else "✓", color = if (t.isConcerning) AlertYellow else EcgGreen, modifier = Modifier.padding(end = 8.dp))
                            Text(t.explanation, style = MaterialTheme.typography.labelSmall, color = Gray500, modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Computed from scans synced by the caregiver's device — not a diagnosis.",
                            style = MaterialTheme.typography.labelSmall, color = Gray700, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VitalMini(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Gray500)
    }
}
