package com.geriatricare.app.data.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.location.LocationManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.geriatricare.app.MainActivity
import com.geriatricare.app.data.model.PhoneLocation
import com.geriatricare.app.data.repository.PatientRepository
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Shares the carried phone's actual fused GPS/network position with the linked
 * family. Started visibly, continues with an OS notification when screen is off.
 * No ESP32, sensor reading, paid Places API, or synthetic coordinate is needed.
 */
@AndroidEntryPoint
class PatientLocationService : Service() {
    @Inject lateinit var repository: PatientRepository
    @Inject lateinit var sharing: PhoneLocationSharing
    @Inject lateinit var auth: FirebaseAuth
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingFixes = Channel<Pair<String, PhoneLocation>>(Channel.CONFLATED)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var patientId = ""
    private var lastQueuedElapsed = 0L
    private var availabilityGeneration = 0L
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private fun locationEnabled() = LocationManagerCompat.isLocationEnabled(locationManager)
    private fun markUnavailable() {
        val id = patientId
        if (id.isBlank()) return
        availabilityGeneration++
        // Discard a queued fix when the provider stops. Do not refresh its timestamp.
        pendingFixes.tryReceive()
        sharing.update(LocationSharingState(id, true, "Phone location unavailable. Last known position only."))
        scope.launch { withTimeoutOrNull(5000L) { repository.markPhoneLocationUnavailable(id) } }
    }
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!locationEnabled()) markUnavailable()
            // Turning Location on does not make an old fix live; wait for a new fix.
        }
    }
    private val authListener = FirebaseAuth.AuthStateListener { if (it.currentUser == null) stopSelf() }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::acceptFix)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable && patientId.isNotBlank()) {
                markUnavailable()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        auth.addAuthStateListener(authListener)
        ContextCompat.registerReceiver(this, providerReceiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            for ((id, fix) in pendingFixes) {
                val generation = availabilityGeneration
                val result = withTimeoutOrNull(15_000L) { repository.updatePhoneLocation(id, fix) }
                if (id != patientId) continue
                if (!locationEnabled() || generation != availabilityGeneration) {
                    // An in-flight fix must not overwrite a subsequent unavailable event.
                    withTimeoutOrNull(5000L) { repository.markPhoneLocationUnavailable(id) }
                    continue
                }
                sharing.update(LocationSharingState(id, true,
                    if (result?.isSuccess == true) "Phone location shared with family."
                    else "Location captured; sync pending. Check your internet connection."))
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(PATIENT_ID).orEmpty()
        if (id.isBlank() || !sharing.hasPermission() || auth.currentUser == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (patientId == id) return START_NOT_STICKY
        val previousId = patientId
        patientId = id
        if (previousId.isNotBlank()) scope.launch { repository.stopPhoneLocationSharing(previousId) }
        lastQueuedElapsed = 0L
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Patient location sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PatientLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Patient location sharing is on")
            .setContentText("This phone's position is shared with the linked family.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            sharing.update(LocationSharingState(id, true, "Getting this phone's current location…"))
            scope.launch { repository.markPhoneLocationUnavailable(id) }
            fused.removeLocationUpdates(callback)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
                .setMaxUpdateAgeMillis(0L)
                .build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    sharing.update(LocationSharingState(id, false, "Phone location is unavailable. Enable Location and try again."))
                    stopSelf()
                }
        } catch (_: RuntimeException) {
            sharing.update(LocationSharingState(id, false, "Allow phone location permission and try again."))
            stopSelf()
        }
        // Restart must come from the visible dashboard, respecting Android's
        // restrictions on starting location services from the background.
        return START_NOT_STICKY
    }

    private fun acceptFix(location: Location) {
        if (!locationEnabled()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (fixAgeMs !in 0..120_000L || !location.hasAccuracy()) return
        if (lastQueuedElapsed != 0L && nowElapsed - lastQueuedElapsed < UPDATE_INTERVAL_MS) return
        val fix = PhoneLocation(location.latitude, location.longitude, location.accuracy,
            System.currentTimeMillis() - fixAgeMs, true, true)
        if (!fix.hasValidFix() || !fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0f) return
        lastQueuedElapsed = nowElapsed
        pendingFixes.trySend(patientId to fix)
    }

    override fun onDestroy() {
        unregisterReceiver(providerReceiver)
        fused.removeLocationUpdates(callback)
        auth.removeAuthStateListener(authListener)
        val id = patientId
        // Use a short independent scope because the service work is cancelled.
        if (id.isNotBlank() && auth.currentUser != null) {
            CoroutineScope(Dispatchers.IO).launch { withTimeoutOrNull(5000L) { repository.stopPhoneLocationSharing(id) } }
        }
        sharing.update(LocationSharingState(id, false, "Location sharing is off. Last known position remains visible to family."))
        pendingFixes.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PATIENT_ID = "patient_id"
        private const val ACTION_STOP = "com.geriatricare.app.STOP_LOCATION_SHARING"
        private const val CHANNEL_ID = "patient_location_sharing"
        private const val NOTIFICATION_ID = 4302
        private const val UPDATE_INTERVAL_MS = 30_000L
    }
}
