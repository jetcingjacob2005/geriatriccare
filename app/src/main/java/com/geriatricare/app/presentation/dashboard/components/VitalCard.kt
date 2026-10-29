package com.geriatricare.app.presentation.dashboard.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.window.Dialog
import com.geriatricare.app.data.model.VitalLevel
import com.geriatricare.app.data.ml.ScreeningReadiness
import com.geriatricare.app.data.ml.ScreeningSnapshot
import com.geriatricare.app.ui.theme.*

/**
 * A vital sign card with animated circular progress arc, live telemetry radar dot,
 * status coloring, and glassmorphic gradient styling.
 */
@Composable
fun VitalCard(
    title    : String,
    value    : String,
    unit     : String,
    icon     : ImageVector,
    level    : VitalLevel,
    progress : Float,           // 0..1 for the arc fill
    accentColor: Color,
    modifier : Modifier = Modifier
) {
    val statusColor = when (level) {
        VitalLevel.NORMAL   -> accentColor
        VitalLevel.WARNING  -> AlertYellow
        VitalLevel.CRITICAL -> AlertRed
    }

    val animatedProgress by animateFloatAsState(
        targetValue   = progress.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label         = "arcProgress"
    )

    // Pulsing glow on critical
    val pulseAlpha by rememberInfiniteTransition(label = "vitalPulse").animateFloat(
        initialValue  = if (level == VitalLevel.CRITICAL) 0.2f else 0f,
        targetValue   = if (level == VitalLevel.CRITICAL) 0.6f else 0f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label         = "pulseA"
    )

    // Live breathing dot for active streaming
    val livePulse by rememberInfiniteTransition(label = "liveDot").animateFloat(
        initialValue  = 0.35f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "livePulse"
    )

    val isActive = value != "--" && value != "0"

    Surface(
        modifier  = modifier,
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = BorderStroke(
            1.dp,
            if (level == VitalLevel.CRITICAL) AlertRed.copy(alpha = 0.6f) else ClinicalBorder
        ),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue))
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(statusColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = statusColor, modifier = Modifier.size(18.dp))
                }
                StatusBadge(level)
            }

            Spacer(Modifier.height(10.dp))

            // Circular arc
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    // Track
                    drawArc(
                        color      = ClinicalBorder,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter  = false,
                        style      = Stroke(8f, cap = StrokeCap.Round)
                    )
                    // Glow (critical only)
                    if (pulseAlpha > 0f) {
                        drawArc(
                            color      = AlertRed.copy(alpha = pulseAlpha),
                            startAngle = 135f,
                            sweepAngle = 270f * animatedProgress,
                            useCenter  = false,
                            style      = Stroke(13f, cap = StrokeCap.Round)
                        )
                    }
                    // Fill
                    drawArc(
                        color      = statusColor,
                        startAngle = 135f,
                        sweepAngle = 270f * animatedProgress,
                        useCenter  = false,
                        style      = Stroke(8f, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                        color = ClinicalText, fontWeight = FontWeight.Bold)
                    Text(unit, style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(EcgGreen.copy(alpha = livePulse), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.labelMedium, color = ClinicalSubtext, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatusBadge(level: VitalLevel) {
    val (text, color) = when (level) {
        VitalLevel.NORMAL   -> "Normal"   to EcgGreen
        VitalLevel.WARNING  -> "Warning"  to AlertYellow
        VitalLevel.CRITICAL -> "Critical" to AlertRed
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text     = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Small report card summarizing the on-device ECG heuristic analysis
 * (lead-off / flatline / irregular-rhythm / normal) shown below the waveform.
 */
@Composable
fun EcgReportCard(
    analysis : com.geriatricare.app.data.model.EcgAnalysis,
    modifier : Modifier = Modifier
) {
    val statusColor = when (analysis.level) {
        VitalLevel.NORMAL   -> EcgGreen
        VitalLevel.WARNING  -> AlertYellow
        VitalLevel.CRITICAL -> AlertRed
    }
    val statusText = when (analysis.status) {
        com.geriatricare.app.data.model.EcgStatus.COLLECTING -> "Analyzing"
        com.geriatricare.app.data.model.EcgStatus.LEAD_OFF   -> "Lead Off"
        com.geriatricare.app.data.model.EcgStatus.FLATLINE   -> "No Signal"
        com.geriatricare.app.data.model.EcgStatus.NOISY      -> "Check Signal"
        com.geriatricare.app.data.model.EcgStatus.IRREGULAR  -> "Irregular"
        com.geriatricare.app.data.model.EcgStatus.NORMAL     -> "Normal"
    }

    Surface(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 6.dp
    ) {
        Row(
            Modifier
                .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).background(statusColor.copy(0.15f), RoundedCornerShape(14.dp)),
                Alignment.Center
            ) {
                Text("❤️", fontSize = 26.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ECG Report", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(color = statusColor.copy(0.15f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, statusColor.copy(0.3f))) {
                        Text(
                            statusText,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(analysis.message, style = MaterialTheme.typography.labelSmall, color = Gray500)
            }

            analysis.estimatedBpm?.let { bpm ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("$bpm", style = MaterialTheme.typography.headlineMedium, color = statusColor, fontWeight = FontWeight.Bold)
                    Text("bpm (est.)", style = MaterialTheme.typography.labelSmall, color = Gray500)
                }
            }
        }
    }
}

@Composable
fun TemperatureCard(
    celsius  : Float,
    level    : VitalLevel,
    modifier : Modifier = Modifier
) {
    val statusColor = when (level) {
        VitalLevel.NORMAL   -> TempOrange
        VitalLevel.WARNING  -> AlertYellow
        VitalLevel.CRITICAL -> AlertRed
    }

    Surface(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 6.dp
    ) {
        Row(
            Modifier
                .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thermometer icon area
            Box(
                Modifier.size(56.dp).background(statusColor.copy(0.15f), RoundedCornerShape(14.dp)),
                Alignment.Center
            ) {
                Text("🌡️", fontSize = 26.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text("Body Temperature", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Text("DS18B20 Sensor", style = MaterialTheme.typography.labelSmall, color = Gray500)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (celsius > 0) "%.1f°C".format(celsius) else "--°C",
                    style = MaterialTheme.typography.headlineMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
                Surface(color = statusColor.copy(0.15f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, statusColor.copy(0.3f))) {
                    Text(
                        when (level) {
                            VitalLevel.NORMAL   -> "Normal"
                            VitalLevel.WARNING  -> "Warning"
                            VitalLevel.CRITICAL -> "Critical"
                        },
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Phone-carried patient location. Tapping opens the coordinate in Google Maps.
 */
@Composable
fun LocationCard(
    lat      : Double,
    lon      : Double,
    hasFix   : Boolean,
    modifier : Modifier = Modifier
) {
    val context = LocalContext.current
    val locationAvailable = hasFix && isValidGpsCoordinate(lat, lon)
    val statusColor = if (locationAvailable) LocationTeal else Gray500

    Surface(
        modifier  = modifier.fillMaxWidth().clickable(enabled = locationAvailable) {
            openLocationMap(context, "geo:$lat,$lon?q=$lat,$lon",
                "https://www.google.com/maps/search/?api=1&query=$lat,$lon")
        },
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 6.dp
    ) {
        Row(
            Modifier
                .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).background(statusColor.copy(0.15f), RoundedCornerShape(14.dp)),
                Alignment.Center
            ) {
                Text("📍", fontSize = 26.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text("Location", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Text(if (locationAvailable) "Patient phone location · tap to open map" else "Start phone location sharing", style = MaterialTheme.typography.labelSmall, color = Gray500)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (locationAvailable) "%.4f, %.4f".format(lat, lon) else "No fix",
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
                Surface(color = statusColor.copy(0.15f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, statusColor.copy(0.3f))) {
                    Text(
                        if (locationAvailable) "Live" else "Searching…",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Shows the trained-model risk probability (0-100%). Only rendered when
 * RiskClassifier.isAvailable — see DashboardViewModel.watchMlRisk().
 */
@Composable
fun MlRiskCard(
    snapshot : ScreeningSnapshot,
    onClick  : () -> Unit = {},
    modifier : Modifier = Modifier
) {
    val score = snapshot.score
    val pct = score?.let { (it * 100).toInt() }
    val statusColor = when {
        score == null       -> Gray500
        score >= 0.5f        -> AlertRed
        score >= 0.25f       -> AlertYellow
        else                 -> AlertGreen
    }

    Surface(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(22.dp),
        color     = ClinicalCard,
        border    = BorderStroke(1.dp, if (score != null && score >= 0.5f) AlertRed.copy(0.5f) else ClinicalBorder),
        tonalElevation = 6.dp,
        onClick   = onClick
    ) {
        Row(
            Modifier
                .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).background(statusColor.copy(0.15f), RoundedCornerShape(14.dp)),
                Alignment.Center
            ) { Text("🧠", fontSize = 26.sp) }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text("AI Screening Score", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                Text(
                    if (score == null) snapshot.status
                    else "${snapshot.status} • tap for contributing factors",
                    style = MaterialTheme.typography.labelSmall, color = Gray500
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pct != null) "$pct%" else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Gray500)
            }
        }
    }
}

@Composable
fun MlRiskDialog(
    snapshot: ScreeningSnapshot,
    onDismiss: () -> Unit
) {
    val effectiveScore = snapshot.score
    val hasScore = effectiveScore != null
    val displayScore = effectiveScore ?: 0f
    val pct = (displayScore * 100).toInt()
    val (riskLabel, statusColor, riskDesc) = when {
        displayScore >= 0.5f -> Triple(
            "Elevated screening signal ($pct%)",
            AlertRed,
            "The XGBoost screening model found a pattern that merits review. This is not a diagnosis and does not independently trigger an emergency alert."
        )
        displayScore >= 0.25f -> Triple(
            "Moderate screening signal ($pct%)",
            AlertYellow,
            "The population-based screening signal is above the lower range. Continue monitoring and consider the current vital-status cards and clinical context."
        )
        else -> Triple(
            "Lower screening signal ($pct%)",
            EcgGreen,
            "The model detected a lower population-pattern signal. It does not confirm that readings are normal or replace clinical judgement."
        )
    }

    val animatedProgress by animateFloatAsState(
        targetValue = displayScore.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "riskGaugeProgress"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ClinicalCard,
            border = BorderStroke(1.dp, ClinicalBorder),
            modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(Brush.verticalGradient(listOf(ClinicalCard, ClinicalPaleBlue)))
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AI Screening Score",
                            style = MaterialTheme.typography.titleMedium,
                            color = ClinicalText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Gray500)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Animated Gauge Arc
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(150.dp, 100.dp)) {
                        val strokeW = 12.dp.toPx()
                        // Track
                        drawArc(
                            color = ClinicalBorder,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            style = Stroke(strokeW, cap = StrokeCap.Round)
                        )
                        // Fill
                        drawArc(
                            color = statusColor,
                            startAngle = 180f,
                            sweepAngle = 180f * animatedProgress,
                            useCenter = false,
                            style = Stroke(strokeW, cap = StrokeCap.Round)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Text(
                            if (hasScore) "$pct%" else "—",
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (!hasScore) "NOT READY" else if (displayScore >= 0.5f) "ELEVATED" else if (displayScore >= 0.25f) "MODERATE" else "LOWER",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (hasScore) riskDesc else snapshot.status,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClinicalText.copy(alpha = 0.9f)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "Model information",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClinicalText,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    if (hasScore) snapshot.status
                    else "No screening score is shown yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClinicalSubtext
                )
                if (hasScore) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Inputs used: HR %.0f bpm (variation %.1f), SpO₂ %.1f%% (variation %.1f), temperature %.1f°C (variation %.2f), ECG %.0f bpm (RR variation %.2f).".format(
                            snapshot.hrMean ?: 0f, snapshot.hrVariation ?: 0f,
                            snapshot.spo2Mean ?: 0f, snapshot.spo2Variation ?: 0f,
                            snapshot.tempMean ?: 0f, snapshot.tempVariation ?: 0f,
                            snapshot.ecgBpm ?: 0f, snapshot.ecgRrVariation ?: 0f
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClinicalSubtext
                    )
                    if (snapshot.samplesCollected < ScreeningReadiness.MAX_HISTORY_SAMPLES) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Initial score may change as more live readings are collected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertYellow
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("AI-assisted screening score. Review it together with the live vital signs.", style = MaterialTheme.typography.bodySmall, color = ClinicalSubtext)
                    Spacer(Modifier.height(12.dp))
                    Text("Contributing factors", style = MaterialTheme.typography.labelMedium, color = ClinicalText)
                    val explanation = snapshot.explanation
                    if (explanation == null) {
                        Text("Model attribution is unavailable for this reading.", style = MaterialTheme.typography.bodySmall, color = ClinicalSubtext)
                    } else {
                        Text("Compared with reference values (%.1f%%). Contributions explain the model output, not a medical cause.".format(explanation.referenceProbability * 100), style = MaterialTheme.typography.bodySmall, color = ClinicalSubtext)
                        explanation.contributions.take(4).forEach { contribution ->
                            Text("${contribution.label}: ${if (contribution.logOdds >= 0) "increased" else "decreased"} the screening signal (%+.3f log-odds).".format(contribution.logOdds),
                                style = MaterialTheme.typography.bodySmall, color = if (contribution.logOdds >= 0) AlertYellow else EcgGreen,
                                modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Observed vital-range checks", style = MaterialTheme.typography.labelMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    snapshot.observedFactors.forEach { factor ->
                        Text("• $factor", style = MaterialTheme.typography.bodySmall, color = ClinicalSubtext, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Navy600)
                Spacer(Modifier.height(8.dp))

                Text(
                    "AI-assisted screening only — not a medical diagnosis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Gray500
                )
            }
        }
    }
}
