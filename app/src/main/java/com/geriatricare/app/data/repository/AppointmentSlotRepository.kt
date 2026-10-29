package com.geriatricare.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.geriatricare.app.data.model.AppointmentSlot
import com.geriatricare.app.data.model.SlotStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppointmentSlotRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection = firestore.collection("appointmentSlots")

    fun getAvailableSlots(doctorId: String, date: String): Flow<List<AppointmentSlot>> = callbackFlow {
        val listener = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val slots = snapshot?.toObjects(AppointmentSlot::class.java)
                    ?.filter {
                        (doctorId.isBlank() || it.doctorId == doctorId) &&
                        (date.isBlank() || it.date == date) &&
                        it.status == SlotStatus.AVAILABLE
                    } ?: emptyList()
                trySend(slots)
            }
        awaitClose { listener.remove() }
    }

    fun getSlotsForDoctor(doctorId: String, date: String): Flow<List<AppointmentSlot>> = callbackFlow {
        val listener = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val slots = snapshot?.toObjects(AppointmentSlot::class.java)
                    ?.filter {
                        (doctorId.isBlank() || it.doctorId == doctorId) &&
                        (date.isBlank() || it.date == date)
                    } ?: emptyList()
                trySend(slots)
            }
        awaitClose { listener.remove() }
    }

    suspend fun createSlot(slot: AppointmentSlot): Result<Unit> = runCatching {
        val documentRef = collection.document()
        val slotWithId = slot.copy(id = documentRef.id)
        documentRef.set(slotWithId).await()
    }

    suspend fun updateSlotStatus(slotId: String, status: SlotStatus): Result<Unit> = runCatching {
        collection.document(slotId).update("status", status.name).await()
    }

    suspend fun createDefaultSlots(doctorId: String, date: String): Result<Unit> = runCatching {
        val defaultTimeSlots = listOf(
            "09:00-09:30", "09:30-10:00", "10:00-10:30", "10:30-11:00",
            "11:00-11:30", "11:30-12:00", "14:00-14:30", "14:30-15:00",
            "15:00-15:30", "15:30-16:00", "16:00-16:30", "16:30-17:00"
        )
        
        firestore.runBatch { batch ->
            defaultTimeSlots.forEach { time ->
                val documentRef = collection.document()
                val slot = AppointmentSlot(
                    id = documentRef.id,
                    doctorId = doctorId,
                    date = date,
                    timeSlot = time,
                    status = SlotStatus.AVAILABLE
                )
                batch.set(documentRef, slot)
            }
        }.await()
    }
}
