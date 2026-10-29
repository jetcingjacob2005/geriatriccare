package com.geriatricare.app.data.repository

import com.geriatricare.app.data.model.CareRequest
import com.geriatricare.app.data.model.CareRequestStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CareRequestRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private fun col(patientId: String) =
        firestore.collection("patients").document(patientId).collection("careRequests")

    /** Real-time list of messages/appointment requests for a patient, newest first. */
    fun getRequests(patientId: String): Flow<List<CareRequest>> = callbackFlow {
        val listener = col(patientId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(CareRequest::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun send(request: CareRequest): Result<Unit> = try {
        col(request.patientId).add(request).await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun updateStatus(patientId: String, requestId: String, status: CareRequestStatus): Result<Unit> = try {
        col(patientId).document(requestId).update("status", status.name).await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
}
