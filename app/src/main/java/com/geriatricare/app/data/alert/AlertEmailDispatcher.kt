package com.geriatricare.app.data.alert

import android.content.Context
import android.util.Log
import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.model.Patient
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dispatches automated, medical-grade HTML emergency alert emails to family members
 * and caretakers whenever critical physiological risks or falls occur.
 *
 * Utilizes standard HTTP REST transport in Dispatchers.IO with multi-recipient routing
 * and formatted emergency telemetry.
 */
object AlertEmailDispatcher {

    private const val TAG = "AlertEmailDispatcher"
    private val gson = Gson()

    // Public reliable email notification endpoint (or webhook)
    private const val EMAIL_SERVICE_ENDPOINT = "https://api.emailjs.com/api/v1.0/email/send"

    data class EmailDispatchResult(
        val success: Boolean,
        val message: String,
        val recipients: List<String>
    )

    /**
     * Sends an emergency alert email to all registered family members and the patient's doctor/caretaker.
     */
    suspend fun dispatchEmergencyAlert(
        context: Context,
        patient: Patient,
        alertTitle: String,
        alertDetails: String,
        vitals: BleData?,
        urgency: String = "CRITICAL"
    ): EmailDispatchResult = withContext(Dispatchers.IO) {
        val recipients = mutableListOf<String>()

        // Add all family emails configured for this patient
        recipients.addAll(patient.familyEmails.filter { it.isNotBlank() })

        // Add doctor/caretaker email if configured
        if (patient.doctorEmail.isNotBlank()) {
            recipients.add(patient.doctorEmail)
        }

        if (recipients.isEmpty()) {
            Log.w(TAG, "No recipient emails found for patient ${patient.name}")
            return@withContext EmailDispatchResult(
                success = false,
                message = "No family or caretaker email addresses registered for ${patient.name}.",
                recipients = emptyList()
            )
        }

        val timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())
        val htmlContent = buildHtmlAlert(patient, alertTitle, alertDetails, vitals, timestamp, urgency)
        val plainText = buildPlainTextAlert(patient, alertTitle, alertDetails, vitals, timestamp, urgency)

        Log.i(TAG, "Dispatching $urgency alert for ${patient.name} to ${recipients.size} recipients: $recipients")

        var anySuccess = false
        val errors = mutableListOf<String>()

        for (email in recipients) {
            try {
                val success = sendSingleEmail(
                    toEmail = email.trim(),
                    patientName = patient.name,
                    subject = "🚨 [URGENT] JeevaMitra Alert: $alertTitle for ${patient.name}",
                    htmlBody = htmlContent,
                    textBody = plainText
                )
                if (success) {
                    anySuccess = true
                    Log.d(TAG, "Successfully dispatched emergency alert to $email")
                } else {
                    errors.add("Failed to deliver to $email")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending alert to $email: ${e.message}", e)
                errors.add("$email: ${e.localizedMessage ?: "Unknown error"}")
            }
        }

        EmailDispatchResult(
            success = anySuccess,
            message = if (anySuccess) "Emergency alert dispatched to ${recipients.size} contacts." else errors.joinToString("; "),
            recipients = recipients
        )
    }

    private fun sendSingleEmail(
        toEmail: String,
        patientName: String,
        subject: String,
        htmlBody: String,
        textBody: String
    ): Boolean {
        return try {
            val payload = JsonObject().apply {
                addProperty("service_id", "geriatricare_alerts")
                addProperty("template_id", "emergency_alert_template")
                addProperty("user_id", "public_service_key")
                val templateParams = JsonObject().apply {
                    addProperty("to_email", toEmail)
                    addProperty("patient_name", patientName)
                    addProperty("subject", subject)
                    addProperty("html_content", htmlBody)
                    addProperty("message", textBody)
                }
                add("template_params", templateParams)
            }

            val conn = (URL(EMAIL_SERVICE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 12_000
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(gson.toJson(payload))
                writer.flush()
            }

            val responseCode = conn.responseCode
            // HTTP 200, 201, 202 are considered successful
            responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Alert email delivery failed: ${e.message}")
            false
        }
    }

    private fun buildHtmlAlert(
        patient: Patient,
        title: String,
        details: String,
        vitals: BleData?,
        timestamp: String,
        urgency: String
    ): String {
        val phoneLocation = patient.phoneLocation?.takeIf { it.hasValidFix() }
        val mapsUrl = phoneLocation?.let {
            "https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}"
        }

        val hrDisplay = vitals?.takeIf { it.hr > 0 }?.let { "${it.hr.toInt()} bpm" } ?: "Not available"
        val spo2Display = vitals?.takeIf { it.spo2 > 0 }?.let { "${it.spo2.toInt()}%" } ?: "Not available"
        val tempDisplay = vitals?.takeIf { it.temp > 0 }?.let { "${String.format(Locale.US, "%.1f", it.temp)} °C" } ?: "Not available"

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b1222; color: #ffffff; margin: 0; padding: 20px; }
            .card { background-color: #162035; border: 1px solid #ff1744; border-radius: 16px; padding: 24px; max-width: 600px; margin: 0 auto; }
            .header { border-bottom: 1px solid #1e2d45; padding-bottom: 16px; margin-bottom: 20px; }
            .badge { background-color: #ff1744; color: #ffffff; padding: 6px 14px; border-radius: 20px; font-weight: bold; font-size: 13px; text-transform: uppercase; display: inline-block; }
            h1 { font-size: 22px; color: #ffffff; margin: 12px 0 6px 0; }
            .time { color: #9e9e9e; font-size: 13px; }
            .alert-box { background-color: rgba(255, 23, 68, 0.15); border-left: 4px solid #ff1744; padding: 14px; border-radius: 8px; margin: 18px 0; font-size: 15px; color: #ff8c99; font-weight: 600; }
            .vitals-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin: 20px 0; }
            .vital-item { background-color: #0f182d; padding: 14px; border-radius: 10px; text-align: center; border: 1px solid #1e2d45; }
            .vital-label { font-size: 12px; color: #9e9e9e; margin-bottom: 4px; }
            .vital-value { font-size: 18px; font-weight: bold; color: #00e5ff; }
            .btn { display: inline-block; background-color: #00e5ff; color: #060c17; text-decoration: none; padding: 12px 24px; border-radius: 10px; font-weight: bold; margin-top: 16px; text-align: center; }
            .footer { font-size: 12px; color: #9e9e9e; margin-top: 24px; text-align: center; border-top: 1px solid #1e2d45; padding-top: 16px; }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="header">
              <span class="badge">$urgency ALERT</span>
              <h1>$title</h1>
              <div class="time">Detected on: $timestamp</div>
            </div>
            
            <div class="alert-box">
              $details
            </div>

            <table style="width: 100%; border-collapse: collapse; margin-bottom: 16px;">
              <tr>
                <td style="color: #9e9e9e; padding: 6px 0;">Patient:</td>
                <td style="font-weight: bold; color: #ffffff;">${patient.name} (${patient.age}y, ${patient.gender})</td>
              </tr>
              <tr>
                <td style="color: #9e9e9e; padding: 6px 0;">Room / Loc:</td>
                <td style="color: #ffffff;">${patient.roomNumber.ifBlank { "Home / Station" }}</td>
              </tr>
              <tr>
                <td style="color: #9e9e9e; padding: 6px 0;">Primary Condition:</td>
                <td style="color: #ffffff;">${patient.condition.ifBlank { "General Monitoring" }}</td>
              </tr>
              <tr>
                <td style="color: #9e9e9e; padding: 6px 0;">Emergency Contact:</td>
                <td style="color: #ffffff;">${patient.emergencyContact} (${patient.emergencyPhone})</td>
              </tr>
            </table>

            <h3 style="color: #ffffff; font-size: 15px; margin: 16px 0 10px 0;">Live Sensor Snapshot:</h3>
            <div class="vitals-grid">
              <div class="vital-item">
                <div class="vital-label">Heart Rate</div>
                <div class="vital-value">$hrDisplay</div>
              </div>
              <div class="vital-item">
                <div class="vital-label">Blood Oxygen</div>
                <div class="vital-value">$spo2Display</div>
              </div>
              <div class="vital-item">
                <div class="vital-label">Temperature</div>
                <div class="vital-value">$tempDisplay</div>
              </div>
            </div>

            ${if (mapsUrl != null) """
            <div style="text-align: center; margin: 20px 0;">
              <a href="$mapsUrl" class="btn" style="background-color: #1de9b6;">📍 View Live Location on Google Maps</a>
            </div>
            """ else ""}

            <div class="footer">
              This is an automated alert from <strong>JeevaMitra AI Health Monitoring System</strong>.<br>
              Please attend to the patient or reach out to emergency services immediately.
            </div>
          </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildPlainTextAlert(
        patient: Patient,
        title: String,
        details: String,
        vitals: BleData?,
        timestamp: String,
        urgency: String
    ): String {
        val phoneLocation = patient.phoneLocation?.takeIf { it.hasValidFix() }
        val mapsUrl = phoneLocation?.let {
            "Patient phone location: https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}"
        } ?: "Patient phone location: not available"

        return """
        🚨 [$urgency ALERT] $title
        
        Patient: ${patient.name} (${patient.age}y, ${patient.gender})
        Room: ${patient.roomNumber.ifBlank { "Home / Station" }}
        Time: $timestamp
        
        Details:
        $details
        
        Live Vitals:
        - Heart Rate: ${vitals?.takeIf { it.hr > 0 }?.let { "${it.hr.toInt()} bpm" } ?: "--"}
        - SpO2: ${vitals?.takeIf { it.spo2 > 0 }?.let { "${it.spo2.toInt()}%" } ?: "--"}
        - Temp: ${vitals?.takeIf { it.temp > 0 }?.let { "${it.temp}°C" } ?: "--"}
        
        $mapsUrl
        
        Emergency Contact: ${patient.emergencyContact} (${patient.emergencyPhone})
        Doctor: ${patient.doctorName} (${patient.doctorPhone})
        
        Sent via JeevaMitra Automated Alert System.
        """.trimIndent()
    }
}
