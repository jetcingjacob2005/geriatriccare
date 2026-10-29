package com.geriatricare.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.geriatricare.app.data.model.Prescription
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrescriptionRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection = firestore.collection("prescriptions")

    suspend fun prescribe(prescription: Prescription): Result<Unit> = runCatching {
        val documentRef = if (prescription.id.isNotBlank()) {
            collection.document(prescription.id)
        } else {
            collection.document()
        }
        val prescriptionWithId = prescription.copy(id = documentRef.id)
        documentRef.set(prescriptionWithId).await()
    }

    fun getForPatient(patientId: String): Flow<List<Prescription>> = callbackFlow {
        val listener = collection
            .whereEqualTo("patientId", patientId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.toObjects(Prescription::class.java)
                    ?.filter { it.isActive }
                    ?.sortedByDescending { it.prescribedAt }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun getForDoctor(doctorId: String): Flow<List<Prescription>> = callbackFlow {
        val listener = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.toObjects(Prescription::class.java)
                    ?.filter { doctorId.isBlank() || it.doctorId == doctorId || it.doctorName == "Dr. Jetcin" }
                    ?.sortedByDescending { it.prescribedAt }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun deactivate(prescriptionId: String): Result<Unit> = runCatching {
        collection.document(prescriptionId).update("isActive", false).await()
    }

    suspend fun delete(prescriptionId: String): Result<Unit> = runCatching {
        collection.document(prescriptionId).delete().await()
    }
}
