package com.geriatricare.app.data.repository

import com.geriatricare.app.data.model.ScanResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanResultRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private fun col(patientId: String) =
        firestore.collection("patients").document(patientId).collection("scanResults")

    suspend fun save(result: ScanResult): Result<Unit> = try {
        col(result.patientId).add(result).await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    /** Historical calendar-day query, independent of the recent-card limit. */
    suspend fun getRange(patientId: String, start: Long, end: Long): List<ScanResult> =
        col(patientId).whereGreaterThanOrEqualTo("timestamp", start).whereLessThan("timestamp", end)
            .orderBy("timestamp", Query.Direction.ASCENDING).limit(6001).get().await()
            .documents.mapNotNull { doc -> doc.toObject(ScanResult::class.java)?.copy(id = doc.id) }

    /** Real-time list, newest first, capped to a reasonable window for a remote viewer. */
    fun getRecent(patientId: String, limit: Long = 60): Flow<List<ScanResult>> = callbackFlow {
        val listener = col(patientId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(ScanResult::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }
}
