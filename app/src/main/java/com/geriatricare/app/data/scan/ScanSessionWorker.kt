package com.geriatricare.app.data.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.geriatricare.app.MainActivity
import com.geriatricare.app.data.bluetooth.BleManager
import com.geriatricare.app.data.model.ConnectionStatus
import com.geriatricare.app.data.model.analyzeEcg
import com.geriatricare.app.data.repository.PatientRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay

/**
 * Runs one scheduled scan session: connects to the patient's ESP32 for a
 * short window (default 2.5 min, not a continuous 24/7 connection),
 * collects readings, then syncs a report summary to Firestore for the family
 * dashboard. Full PDF sharing remains an explicit caregiver WhatsApp action.
 * When it finishes
 * (or if it can't connect), it queues the next morning/evening slot.
 */
@HiltWorker
class ScanSessionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val bleManager: BleManager,
    private val patientRepo: PatientRepository,
    private val scanResultRepo: com.geriatricare.app.data.repository.ScanResultRepository
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "scan_session_channel"
        private const val NOTIFICATION_ID = 4201
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val TEMP_SAMPLE_INTERVAL_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        val patientId = inputData.getString("patientId") ?: return Result.failure()
        val savedAddress = inputData.getString("bleDeviceAddress").orEmpty()

        setForeground(foregroundInfo("Taking scheduled vitals scan…"))

        val patient = patientRepo.getPatient(patientId)
        val address = savedAddress.ifBlank { patient?.bleDeviceAddress.orEmpty() }

        if (patient == null) return Result.success() // patient deleted — don't reschedule

        if (address.isBlank()) {
            // No paired ESP32 — skip this slot silently and try again next slot.
            ScanScheduler.scheduleNext(applicationContext, patientId, address)
            return Result.success()
        }

        bleManager.connectToAddress(address)
        val connected = waitForConnection(CONNECT_TIMEOUT_MS)

        val tempSamples = mutableListOf<Float>()
        if (connected) {
            val endTime = System.currentTimeMillis() + ScanScheduler.SCAN_DURATION_MS
            while (System.currentTimeMillis() < endTime) {
                val t = bleManager.bleData.value.temp
                if (t > 0f) tempSamples += t
                delay(TEMP_SAMPLE_INTERVAL_MS)
            }
        }

        val ecgAnalysis = bleManager.ecgBuffer.value.analyzeEcg(bleManager.ecgSampleRate.value)
        val latestVitals = bleManager.bleData.value.copy(
            temp = if (tempSamples.isNotEmpty()) tempSamples.average().toFloat() else bleManager.bleData.value.temp
        )

        bleManager.disconnect()

        val insight = com.geriatricare.app.data.insight.HealthInsightGenerator.generate(latestVitals, ecgAnalysis)
        // This Firestore summary updates the family dashboard automatically the
        // next time it is open. It needs no paid Storage/Functions service.
        scanResultRepo.save(
            com.geriatricare.app.data.model.ScanResult(
                patientId   = patientId,
                temp        = latestVitals.temp,
                spo2        = latestVitals.spo2,
                hr          = latestVitals.hr,
                ecgStatus   = ecgAnalysis.status.name,
                ecgMessage  = ecgAnalysis.message,
                ecgBpm      = ecgAnalysis.estimatedBpm,
                insightText = insight
            )
        )

        // Queue the next morning/evening slot.
        ScanScheduler.scheduleNext(applicationContext, patientId, address)
        return Result.success()
    }

    private suspend fun waitForConnection(timeoutMs: Long): Boolean {
        val endTime = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endTime) {
            if (bleManager.connectionState.value.status == ConnectionStatus.CONNECTED) return true
            delay(500)
        }
        return bleManager.connectionState.value.status == ConnectionStatus.CONNECTED
    }

    private fun foregroundInfo(message: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Scheduled Scans", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("JeevaMitra")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
