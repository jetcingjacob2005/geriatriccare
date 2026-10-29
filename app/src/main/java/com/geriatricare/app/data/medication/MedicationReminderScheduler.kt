package com.geriatricare.app.data.medication

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.geriatricare.app.data.model.MedicationReminder
import com.geriatricare.app.data.model.ReminderStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists local dose reminders using WorkManager. WorkManager survives app process
 * death and device reboot, but Android may defer delivery under extreme battery-saving
 * conditions; it deliberately avoids the intrusive exact-alarm permission.
 */
@Singleton
class MedicationReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun sync(reminders: List<MedicationReminder>) {
        reminders.forEach { reminder ->
            if (reminder.status == ReminderStatus.PENDING) schedule(reminder)
            else cancel(reminder.id)
        }
    }

    fun schedule(reminder: MedicationReminder) {
        if (reminder.id.isBlank()) return
        val dueAt = parseDueTime(reminder) ?: return
        // A reminder that is more than 20 minutes old should be reviewed in the app,
        // rather than unexpectedly notifying a patient long after the dose time.
        val delay = dueAt - System.currentTimeMillis()
        if (delay < -MAX_LATE_DELIVERY_MS) return

        val data = Data.Builder()
            .putString(MedicationNotificationWorker.KEY_REMINDER_ID, reminder.id)
            .putString(MedicationNotificationWorker.KEY_MEDICATION, reminder.medicationName)
            .putString(MedicationNotificationWorker.KEY_DOSAGE, reminder.dosage)
            .putString(MedicationNotificationWorker.KEY_INSTRUCTION, reminder.instructions.ifBlank { reminder.mealTiming })
            .build()
        val request = OneTimeWorkRequestBuilder<MedicationNotificationWorker>()
            .setInputData(data)
            .setInitialDelay(delay.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(workName(reminder.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(reminder.id), ExistingWorkPolicy.KEEP, request
        )
    }

    fun cancel(reminderId: String) {
        if (reminderId.isNotBlank()) WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
    }

    private fun parseDueTime(reminder: MedicationReminder): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
            .parse("${reminder.date.trim()} ${reminder.scheduledTime.trim()}")
            ?.time
    }.getOrNull()

    private fun workName(id: String) = "medication_reminder_$id"

    private companion object {
        const val MAX_LATE_DELIVERY_MS = 20 * 60 * 1000L
    }
}
