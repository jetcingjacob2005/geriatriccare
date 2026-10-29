package com.geriatricare.app.data.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geriatricare.app.MainActivity
import com.geriatricare.app.R
import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.model.Patient
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmergencyAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // In-memory debounce map: key = "$patientId-$alertType", value = lastDispatchTimeMs
    private val lastAlertTimestamps = ConcurrentHashMap<String, Long>()

    companion object {
        private const val TAG = "EmergencyAlertManager"
        private const val CHANNEL_ID = "emergency_alerts"
        private const val CHANNEL_NAME = "Critical Health & Fall Alerts"
        private const val DEBOUNCE_COOLDOWN_MS = 180_000L // 3 minutes debounce per alert type
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority notifications for falls, prolonged motionlessness, and vital anomalies."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Triggers an emergency alert when a fall or prolonged motionless state is detected.
     */
    fun triggerFallAlert(
        patient: Patient,
        isUnconscious: Boolean,
        fallDurationMs: Long = 0L,
        vitals: BleData? = null
    ) {
        val alertType = if (isUnconscious) "UNCONSCIOUS" else "FALL"
        val urgency = if (isUnconscious) "CRITICAL" else "HIGH"
        val durationSec = (fallDurationMs / 1000).coerceAtLeast(1)

        val title = if (isUnconscious) {
            "CRITICAL: Motionless After Fall — Check Immediately"
        } else {
            "WARNING: Patient Fall Detected!"
        }

        val details = if (isUnconscious) {
            "Patient ${patient.name} has remained motionless after a confirmed fall for ${durationSec}s. Check the patient immediately; camera data cannot determine consciousness."
        } else {
            "Patient ${patient.name} has suffered a sudden fall. Moving on floor — check on patient immediately."
        }

        dispatchAlert(
            patient = patient,
            alertType = alertType,
            title = title,
            details = details,
            vitals = vitals,
            urgency = urgency
        )
    }

    /**
     * Triggers an alert when one or more vital signs deviate into severe clinical ranges.
     */
    fun triggerVitalAnomalyAlert(
        patient: Patient,
        anomalySummary: String,
        vitals: BleData
    ) {
        val alertType = "VITAL_ANOMALY"
        val title = "CRITICAL: Abnormal Vital Signs Detected"
        val details = "Patient ${patient.name} is exhibiting critical physiological parameters: $anomalySummary. Review vitals immediately."

        dispatchAlert(
            patient = patient,
            alertType = alertType,
            title = title,
            details = details,
            vitals = vitals,
            urgency = "CRITICAL"
        )
    }

    /**
     * Triggers an alert when the AI Risk Classifier predicts elevated adverse risk.
     */
    fun triggerMlRiskAlert(
        patient: Patient,
        riskScore: Float,
        vitals: BleData
    ) {
        val alertType = "ML_HIGH_RISK"
        val pct = (riskScore * 100).toInt()
        val title = "AI Health Warning: High Deterioration Risk ($pct%)"
        val details = "The on-device clinical risk model (VitalDB trained) detected a high probability ($pct%) of acute physiological deterioration based on rolling vitals and ECG dynamics."

        dispatchAlert(
            patient = patient,
            alertType = alertType,
            title = title,
            details = details,
            vitals = vitals,
            urgency = "HIGH"
        )
    }

    private fun dispatchAlert(
        patient: Patient,
        alertType: String,
        title: String,
        details: String,
        vitals: BleData?,
        urgency: String
    ) {
        val debounceKey = "${patient.id}-$alertType"
        val now = System.currentTimeMillis()
        val lastTime = lastAlertTimestamps[debounceKey] ?: 0L

        // Debounce repeated identical alerts within cooldown window
        if (now - lastTime < DEBOUNCE_COOLDOWN_MS) {
            Log.d(TAG, "Suppressed duplicate alert ($debounceKey) within cooldown window.")
            return
        }
        lastAlertTimestamps[debounceKey] = now

        scope.launch {
            Log.w(TAG, "Executing multi-channel alert dispatch for ${patient.name} [Type: $alertType]")

            // Play phone message beep sound & vibration
            AlertSoundPlayer.playMessageBeep(context)

            // 1. Post High-Priority Android System Notification
            postSystemNotification(patient, title, details, urgency)

            // 2. Automated Email Dispatch to Caretaker and Registered Family Members
            val emailResult = AlertEmailDispatcher.dispatchEmergencyAlert(
                context = context,
                patient = patient,
                alertTitle = title,
                alertDetails = details,
                vitals = vitals,
                urgency = urgency
            )
            Log.d(TAG, "Email dispatch result: ${emailResult.message}")

            // 3. Sync Alert Record to Firestore Collection for Remote Family Dashboard
            syncAlertToFirestore(patient, alertType, title, details, vitals, urgency, now)
        }
    }

    private fun postSystemNotification(
        patient: Patient,
        title: String,
        details: String,
        urgency: String
    ) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("patientId", patient.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                patient.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                .setColor(0xFFFF1744.toInt()) // AlertRed
                .build()

            val notificationId = (patient.id.hashCode() xor alertCounter++)
            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post system notification: ${e.message}")
        }
    }

    private fun syncAlertToFirestore(
        patient: Patient,
        alertType: String,
        title: String,
        details: String,
        vitals: BleData?,
        urgency: String,
        timestamp: Long
    ) {
        try {
            val alertData = hashMapOf(
                "id" to UUID.randomUUID().toString(),
                "patientId" to patient.id,
                "patientName" to patient.name,
                "roomNumber" to patient.roomNumber,
                "alertType" to alertType,
                "title" to title,
                "details" to details,
                "urgency" to urgency,
                "timestamp" to timestamp,
                "hr" to (vitals?.hr ?: 0f),
                "spo2" to (vitals?.spo2 ?: 0f),
                "temp" to (vitals?.temp ?: 0f),
                "acknowledged" to false
            )

            // Save under patients/{patientId}/alerts subcollection
            firestore.collection("patients")
                .document(patient.id)
                .collection("alerts")
                .add(alertData)
                .addOnSuccessListener {
                    Log.d(TAG, "Alert synced to Firestore successfully.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync alert to Firestore: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error packaging Firestore alert: ${e.message}")
        }
    }

    private var alertCounter = 1000
}
