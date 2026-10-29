package com.geriatricare.app.data.medication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.geriatricare.app.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Posts one on-device medication notification at the time scheduled by a clinician. */
@HiltWorker
class MedicationNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Android 13+ will otherwise reject the notification. The dashboard asks for
        // this permission when it is first opened, so no medication information is sent
        // anywhere just to display a reminder.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val medication = inputData.getString(KEY_MEDICATION).orEmpty()
        if (medication.isBlank()) return Result.failure()
        val dosage = inputData.getString(KEY_DOSAGE).orEmpty()
        val instruction = inputData.getString(KEY_INSTRUCTION).orEmpty()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Medication reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Dose reminders scheduled by JeevaMitra"
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            inputData.getString(KEY_REMINDER_ID).orEmpty().hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = listOf(dosage, instruction).filter { it.isNotBlank() }.joinToString(" • ")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Time for medication")
            .setContentText(if (detail.isBlank()) medication else "$medication — $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (detail.isBlank()) medication else "$medication\n$detail"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        manager.notify(inputData.getString(KEY_REMINDER_ID).orEmpty().hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "medication_reminders"
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_MEDICATION = "medication"
        const val KEY_DOSAGE = "dosage"
        const val KEY_INSTRUCTION = "instruction"
    }
}
