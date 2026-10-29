package com.geriatricare.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.geriatricare.app.data.model.MedicationReminder
import com.geriatricare.app.data.model.ReminderStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationReminderRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection = firestore.collection("medicationReminders")

    suspend fun create(reminder: MedicationReminder): Result<Unit> = runCatching {
        val documentRef = collection.document()
        val reminderWithId = reminder.copy(id = documentRef.id)
        documentRef.set(reminderWithId).await()
    }

    fun getTodayForPatient(patientId: String, todayDate: String): Flow<List<MedicationReminder>> = callbackFlow {
        val listener = collection
            .whereEqualTo("patientId", patientId)
            .whereEqualTo("date", todayDate)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val reminders = snapshot.toObjects(MedicationReminder::class.java)
                    trySend(reminders)
                }
            }
        awaitClose { listener.remove() }
    }

    /**
     * Used only on the patient's device to create local OS notifications for
     * upcoming prescribed doses. Filtering dates locally avoids requiring a
     * Firestore composite index for the two equality/range fields.
     */
    fun getUpcomingForPatient(patientId: String, fromDate: String): Flow<List<MedicationReminder>> = callbackFlow {
        val listener = collection
            .whereEqualTo("patientId", patientId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val reminders = snapshot?.toObjects(MedicationReminder::class.java).orEmpty()
                    .filter { it.status == ReminderStatus.PENDING && it.date >= fromDate }
                trySend(reminders)
            }
        awaitClose { listener.remove() }
    }

    suspend fun markTaken(reminderId: String): Result<Unit> = runCatching {
        collection.document(reminderId).update("status", ReminderStatus.TAKEN.name).await()
    }

    suspend fun markSkipped(reminderId: String): Result<Unit> = runCatching {
        collection.document(reminderId).update("status", ReminderStatus.SKIPPED.name).await()
    }

    suspend fun delete(reminderId: String): Result<Unit> = runCatching {
        collection.document(reminderId).delete().await()
    }

    suspend fun deleteForPrescription(prescriptionId: String): Result<Unit> = runCatching {
        val snapshot = collection.whereEqualTo("prescriptionId", prescriptionId).get().await()
        for (doc in snapshot.documents) {
            doc.reference.delete().await()
        }
    }
}
