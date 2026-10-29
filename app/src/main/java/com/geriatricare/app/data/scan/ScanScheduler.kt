package com.geriatricare.app.data.scan

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules twice-daily scan sessions (8:00 AM and 6:00 PM) instead of
 * continuous 24/7 monitoring — the ESP32/sensors only need to be worn
 * and connected briefly for each scan.
 *
 * Each patient has their own independent schedule (unique work name per
 * patientId), since a nurse/caregiver may be tracking more than one patient.
 */
object ScanScheduler {

    const val SCAN_DURATION_MS = 150_000L // 2.5 minutes per scan session

    private val SLOT_HOURS = listOf(8, 18) // 8:00 AM and 6:00 PM (24h clock)

    private fun workName(patientId: String) = "scan_session_$patientId"

    /** Call once to turn scheduled scans ON for a patient (also called internally after each run to queue the next slot). */
    fun scheduleNext(context: Context, patientId: String, bleDeviceAddress: String) {
        val delayMs = millisUntilNextSlot()
        val input = Data.Builder()
            .putString("patientId", patientId)
            .putString("bleDeviceAddress", bleDeviceAddress)
            .build()

        val request = OneTimeWorkRequestBuilder<ScanSessionWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(workName(patientId))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(patientId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Call to turn scheduled scans OFF for a patient. */
    fun cancel(context: Context, patientId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(patientId))
    }

    fun isScheduledFlow(context: Context, patientId: String): kotlinx.coroutines.flow.Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(patientId))

    private fun millisUntilNextSlot(): Long {
        val now = Calendar.getInstance()
        var soonest: Calendar? = null
        for (hour in SLOT_HOURS) {
            val candidate = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= now.timeInMillis) {
                candidate.add(Calendar.DAY_OF_YEAR, 1)
            }
            if (soonest == null || candidate.timeInMillis < soonest!!.timeInMillis) {
                soonest = candidate
            }
        }
        return (soonest!!.timeInMillis - now.timeInMillis).coerceAtLeast(1000L)
    }
}
