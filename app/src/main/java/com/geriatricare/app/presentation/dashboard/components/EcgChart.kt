package com.geriatricare.app.presentation.dashboard.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.content.Intent
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.geriatricare.app.ui.theme.*
import com.geriatricare.app.data.model.EcgAnalysis
import com.geriatricare.app.data.model.EcgStatus
import com.geriatricare.app.data.model.canDisplayEcg
import com.geriatricare.app.data.model.isEcgClipped
import com.geriatricare.app.data.model.EcgDisplayWindow

/**
 * Scrolling real-time ECG waveform chart.
 * Renders the last [ecgPoints] samples as a path on a dark canvas with grid lines.
 * Contact gaps are hidden. Display scaling never changes stored samples or analysis.
 */
@Composable
fun EcgChart(
    ecgPoints  : List<Float>,
    isConnected: Boolean,
    modifier   : Modifier = Modifier,
    analysis: EcgAnalysis = EcgAnalysis(EcgStatus.COLLECTING, "Waiting for usable ECG"),
    sampleRateHz: Float = 50f
) {
    var showMeasurements by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (showMeasurements) EcgMeasurementsDialog(analysis.measurements,
        onExport = if (isConnected && canDisplayEcg(ecgPoints, analysis)) ({
            // Capture immutable values before IO; sharing requires an explicit tap.
            val samples = ecgPoints.takeLastWhile { it.isFinite() && it in 0f..1023f }.toList()
            val rate = sampleRateHz.toInt()
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "ecg_exports").apply { mkdirs() }
                        val now = System.currentTimeMillis()
                        File(dir,"ecg_$now.json").apply {
                            writeText(com.geriatricare.app.data.model.EcgSnapshot.json(samples,rate,now))
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newUri(context.contentResolver, "ECG snapshot", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share,"Share ECG snapshot"))
                }.onFailure { Toast.makeText(context,"ECG export unavailable",Toast.LENGTH_SHORT).show() }
            }
        }) else null,
        onDismiss = { showMeasurements = false })
    val plotSignal = isConnected && canDisplayEcg(ecgPoints, analysis)
    val trace = ecgPoints.takeLastWhile { it.isFinite() && it in 0f..1023f }
    val clipped = isEcgClipped(ecgPoints)
    val qualityWarning = clipped || analysis.status in setOf(EcgStatus.NOISY, EcgStatus.FLATLINE)
    val traceColor = if (qualityWarning) AlertYellow else EcgGreen
    val displayMaxPoints = EcgDisplayWindow.maxSamples(sampleRateHz.toInt())
    val statusLabel = when {
        !isConnected -> "OFFLINE"
        ecgPoints.lastOrNull()?.let { it < 0f } == true -> "NO CONTACT"
        clipped -> "UNUSABLE SIGNAL"
        plotSignal && qualityWarning -> "CHECK SIGNAL"
        plotSignal -> "LIVE SIGNAL"
        else -> "NO SIGNAL"
    }
    val statusColor = when {
        !isConnected -> Gray500
        clipped || plotSignal && qualityWarning -> AlertYellow
        plotSignal -> EcgGreen
        else -> Gray500
    }

    // Animate the scan-line glow alpha when connected
    val glowAlpha by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue  = 0.3f,
        targetValue   = 0.9f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "glow"
    )

    Surface(
        modifier  = modifier,
        shape     = RoundedCornerShape(20.dp),
        color     = ClinicalCard,
        border    = androidx.compose.foundation.BorderStroke(1.dp, ClinicalBorder),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("ECG Waveform", style = MaterialTheme.typography.titleMedium, color = ClinicalText, fontWeight = FontWeight.SemiBold)
                    Text("Single-lead ECG · 6-second live rhythm strip", style = MaterialTheme.typography.labelSmall, color = ClinicalSubtext)
                    TextButton(onClick = { showMeasurements = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Measurements", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // Live indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) {
                        drawCircle(color = statusColor)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .background(ClinicalPaleBlue, RoundedCornerShape(12.dp))
            ) {
                val w = size.width
                val h = size.height

                // Grid lines
                drawGrid(w, h)

                if (plotSignal) {
                    // Show only the acquisition since the most recent contact gap.
                    // This is intentionally display-only; raw ECG remains the source of
                    // analysis, export, alerts, and all clinical-status messaging.
                    drawEcgPath(
                        EcgDisplayWindow.smoothForDisplay(trace, sampleRateHz.toInt()),
                        w, h, glowAlpha, traceColor, displayMaxPoints
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(w: Float, h: Float) {
    val minorGridColor = Color(0xFFDCEAF0)
    val majorGridColor = Color(0xFFB8D3DE)
    val cols = EcgDisplayWindow.DISPLAY_SECONDS // One major column per second.
    val rows = 4
    repeat(cols + 1) { i ->
        val x = w * i / cols
        drawLine(majorGridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 0.8f)
        if (i < cols) {
            for (minor in 1 until 5) {
                val minorX = x + (w / cols) * minor / 5f
                drawLine(minorGridColor, Offset(minorX, 0f), Offset(minorX, h), strokeWidth = 0.45f)
            }
        }
    }
    repeat(rows + 1) { i ->
        val y = h * i / rows
        drawLine(majorGridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.8f)
        if (i < rows) {
            for (minor in 1 until 5) {
                val minorY = y + (h / rows) * minor / 5f
                drawLine(minorGridColor, Offset(0f, minorY), Offset(w, minorY), strokeWidth = 0.45f)
            }
        }
    }
}

private fun DrawScope.drawEcgPath(
    points: List<Float>, w: Float, h: Float, glowAlpha: Float,
    traceColor: Color, maxPoints: Int
) {
    val totalCount = points.size
    val startIdx = if (totalCount > maxPoints) totalCount - maxPoints else 0
    val count = totalCount - startIdx
    if (count < 2) return

    // Restore baseline centring while bounding magnification of weak/noisy voltage.
    val (minVal, maxVal) = EcgDisplayWindow.bounds(points.subList(startIdx, totalCount))
    val rangeTotal = maxVal - minVal

    // Construct single path
    val path = Path()
    var lastX = 0f
    var lastY = h / 2

    for (i in 0 until count) {
        val v = points[startIdx + i]
        val x = EcgDisplayWindow.x(i, count, w, maxPoints)
        val y = if (v < 0f) {
            h / 2 // Lead-off flatline
        } else {
            h - ((v - minVal) / rangeTotal).coerceIn(0.02f, 0.98f) * h
        }

        if (!v.isFinite() || v !in 0f..1023f) continue
        if (i == 0 || !points[startIdx + i - 1].isFinite() || points[startIdx + i - 1] !in 0f..1023f) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
        lastX = x
        lastY = y
    }

    // Draw glow layer (thick, translucent)
    drawPath(
        path  = path,
        color = traceColor.copy(alpha = glowAlpha * 0.12f),
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Draw crisp ECG line
    drawPath(
        path  = path,
        color = traceColor,
        style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Draw leading edge pulse indicator
    drawCircle(
        color  = traceColor.copy(alpha = glowAlpha),
        radius = 4f,
        center = Offset(lastX, lastY)
    )
}
