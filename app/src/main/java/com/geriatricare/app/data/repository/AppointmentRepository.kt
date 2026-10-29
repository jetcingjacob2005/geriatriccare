package com.geriatricare.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.geriatricare.app.data.model.Appointment
import com.geriatricare.app.data.model.AppointmentStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppointmentRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection = firestore.collection("appointments")

    suspend fun create(appointment: Appointment): Result<Unit> = runCatching {
        val documentRef = collection.document()
        val appointmentWithId = appointment.copy(id = documentRef.id)
        documentRef.set(appointmentWithId).await()
    }

    fun getForPatient(patientId: String): Flow<List<Appointment>> = callbackFlow {
        val listener = collection
            .whereEqualTo("patientId", patientId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.toObjects(Appointment::class.java)
                    ?.sortedByDescending { it.createdAt }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun getForDoctor(doctorId: String): Flow<List<Appointment>> = callbackFlow {
        val listener = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.toObjects(Appointment::class.java)
                    ?.filter { doctorId.isBlank() || it.doctorId == doctorId || it.doctorName == "Dr. Jetcin" }
                    ?.sortedByDescending { it.createdAt }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    /** Stream of all appointments across the hospital for Doctor review */
    fun getAllAppointments(): Flow<List<Appointment>> = callbackFlow {
        val listener = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.toObjects(Appointment::class.java)
                    ?.sortedByDescending { it.createdAt }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun updateStatus(appointmentId: String, status: AppointmentStatus, notes: String = ""): Result<Unit> = runCatching {
        collection.document(appointmentId)
            .update(
                mapOf(
                    "status" to status.name,
                    "notes" to notes
                )
            ).await()
    }

    suspend fun delete(appointmentId: String): Result<Unit> = runCatching {
        collection.document(appointmentId).delete().await()
    }

    suspend fun getById(appointmentId: String): Appointment? = runCatching {
        collection.document(appointmentId).get().await().toObject(Appointment::class.java)
    }.getOrNull()
}
