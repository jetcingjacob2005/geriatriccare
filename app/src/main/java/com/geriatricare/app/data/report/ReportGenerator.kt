package com.geriatricare.app.data.report

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.model.EcgAnalysis
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.model.heartRateLevel
import com.geriatricare.app.data.model.spO2Level
import com.geriatricare.app.data.model.temperatureLevel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a one-page PDF vitals/ECG summary report for a patient and saves
 * it under the app's cache dir (shareable via FileProvider, authority
 * "<applicationId>.fileprovider" — see AndroidManifest + res/xml/file_paths.xml).
 *
 * This is a plain-language summary for family/caregivers, not a clinical
 * diagnostic document — the report explicitly says so at the bottom.
 */
object ReportGenerator {

    private const val PAGE_WIDTH  = 595  // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN      = 40f

    fun generate(
        context : Context,
        patient : Patient?,
        vitals  : BleData,
        ecg     : EcgAnalysis,
        insightOverride: String? = null
    ): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply { color = Color.rgb(20, 40, 70); textSize = 20f; isFakeBoldText = true }
        val headingPaint = Paint().apply { color = Color.rgb(20, 40, 70); textSize = 14f; isFakeBoldText = true }
        val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        val valuePaint = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val smallPaint = Paint().apply { color = Color.GRAY; textSize = 9f }
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        var y = MARGIN

        canvas.drawText("JeevaMitra — Health Report", MARGIN, y, titlePaint)
        y += 18
        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        canvas.drawText("Generated: $dateStr", MARGIN, y, smallPaint)
        y += 20
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 24

        // ── Patient Info ─────────────────────────────────────────────────────
        canvas.drawText("Patient Information", MARGIN, y, headingPaint)
        y += 20
        fun row(label: String, value: String) {
            canvas.drawText(label, MARGIN, y, labelPaint)
            canvas.drawText(value.ifBlank { "—" }, MARGIN + 140, y, valuePaint)
            y += 18
        }
        row("Name", patient?.name ?: "—")
        row("Age", if ((patient?.age ?: 0) > 0) "${patient?.age}" else "—")
        row("Gender", patient?.gender ?: "—")
        row("Room No.", patient?.roomNumber ?: "—")
        row("Blood Group", patient?.bloodGroup ?: "—")
        row("Primary Condition", patient?.condition ?: "—")
        y += 10
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 24

        // ── Vitals Snapshot ──────────────────────────────────────────────────
        canvas.drawText("Vitals Snapshot", MARGIN, y, headingPaint)
        y += 20
        fun riskRow(label: String, value: String, level: com.geriatricare.app.data.model.VitalLevel?) {
            canvas.drawText(label, MARGIN, y, labelPaint)
            canvas.drawText(value, MARGIN + 140, y, valuePaint)
            if (level != null && level != com.geriatricare.app.data.model.VitalLevel.NORMAL) {
                val tagPaint = Paint().apply {
                    color = if (level == com.geriatricare.app.data.model.VitalLevel.CRITICAL) Color.rgb(200, 30, 30) else Color.rgb(200, 130, 0)
                    textSize = 10f
                    isFakeBoldText = true
                }
                canvas.drawText(if (level == com.geriatricare.app.data.model.VitalLevel.CRITICAL) "⚠ RISK" else "⚠ BORDERLINE", MARGIN + 320, y, tagPaint)
            }
            y += 18
        }
        riskRow("Body Temperature", if (vitals.temp > 0) "%.1f °C".format(vitals.temp) else "No reading",
            if (vitals.temp > 0) vitals.temp.temperatureLevel() else null)
        riskRow("SpO2 (normal: 95–100%)", if (vitals.spo2 > 0) "${vitals.spo2.toInt()} %" else "Sensor not connected",
            if (vitals.spo2 > 0) vitals.spo2.spO2Level() else null)
        riskRow("Heart Rate (normal: 60–100 bpm)", if (vitals.hr > 0) "${vitals.hr.toInt()} bpm" else "Sensor not connected",
            if (vitals.hr > 0) vitals.hr.heartRateLevel() else null)
        y += 10
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 24

        // ── ECG Report ───────────────────────────────────────────────────────
        canvas.drawText("ECG Report", MARGIN, y, headingPaint)
        y += 20
        row("Status", ecg.status.name.replace('_', ' '))
        ecg.estimatedBpm?.let { riskRow("Estimated BPM (from ECG)", "$it bpm", ecg.rateLevel) }
        y += 4
        val notePaint = Paint().apply { color = Color.rgb(40, 40, 40); textSize = 11f }
        canvas.drawText(ecg.message, MARGIN, y, notePaint)
        y += 20
        val ecgScopeNote = "This single-lead summary is not diagnostic. Experimental interval estimates, when " +
            "available in the app, are unvalidated and are not used for diagnosis or this screening report."
        y = drawWrappedText(canvas, ecgScopeNote, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, smallPaint)
        y += 20

        // ── Plain-Language Summary ──────────────────────────────────────────
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20
        canvas.drawText("Plain-Language Summary", MARGIN, y, headingPaint)
        y += 18
        val insightText = insightOverride ?: com.geriatricare.app.data.insight.HealthInsightGenerator.generate(vitals, ecg)
        val insightPaint = Paint().apply { color = Color.rgb(30, 30, 30); textSize = 11f }
        y = drawWrappedText(canvas, insightText, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, insightPaint)
        y += 20

        // ── Disclaimer ───────────────────────────────────────────────────────
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        y += 20
        val disclaimer = "This report is generated from a home/DIY monitoring device for general awareness " +
            "only. It is not a certified medical device, and both the ECG analysis and the plain-language " +
            "summary above are basic on-device estimates, not a clinical diagnosis. Please consult a doctor " +
            "for any health concerns or before making medical decisions."
        drawWrappedText(canvas, disclaimer, MARGIN, y, PAGE_WIDTH - 2 * MARGIN, smallPaint)

        document.finishPage(page)

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val fileNameSafe = (patient?.name?.ifBlank { "patient" } ?: "patient").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "JeevaMitra_${fileNameSafe}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    fun shareUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun drawWrappedText(canvas: android.graphics.Canvas, text: String, x: Float, startY: Float, maxWidth: Float, paint: Paint): Float {
        val words = text.split(" ")
        var line = StringBuilder()
        var y = startY
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth) {
                canvas.drawText(line.toString(), x, y, paint)
                y += 12
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, y, paint)
        return y
    }
}
