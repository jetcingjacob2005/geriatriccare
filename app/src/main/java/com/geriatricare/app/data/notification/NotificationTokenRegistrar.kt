package com.geriatricare.app.data.notification

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

/** Stores this signed-in device's FCM token. Sending always happens in a trusted
 * Cloud Function; the Android app never contains a server credential. */
object NotificationTokenRegistrar {
    private const val TAG = "NotificationTokenRegistrar"

    fun registerCurrentDevice() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> save(uid, token) }
            .addOnFailureListener { error -> Log.w(TAG, "Unable to obtain notification token", error) }
    }

    fun save(uid: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("deviceTokens").document(token)
            .set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            .addOnFailureListener { error -> Log.w(TAG, "Unable to save notification token", error) }
    }
}
