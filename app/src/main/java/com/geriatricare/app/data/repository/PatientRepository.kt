package com.geriatricare.app.data.repository

import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.model.PhoneLocation
import com.google.firebase.firestore.SetOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatientRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val col get() = firestore.collection("patients")

    /** Real-time list of patients for the current doctor */
    fun getPatients(): Flow<List<Patient>> = callbackFlow {
        val doctorId = auth.currentUser?.uid ?: run { trySend(emptyList()); close(); return@callbackFlow }
        val listener = col.whereEqualTo("doctorId", doctorId)
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(Patient::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getPatient(id: String): Patient? = try {
        val doc = col.document(id).get().await()
        doc.toObject(Patient::class.java)?.copy(id = doc.id)
    } catch (_: Exception) { null }

    /** Real-time flow for a single patient document (e.g. for live fall telemetry & camera feed) */
    fun getPatientFlow(id: String): Flow<Patient?> = callbackFlow {
        val listener = col.document(id).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            val p = snap.toObject(Patient::class.java)?.copy(id = snap.id)
            trySend(p)
        }
        awaitClose { listener.remove() }
    }

    /** One-shot check used right after login to decide caregiver vs family routing. */
    suspend fun getPatientsOnce(): List<Patient> = try {
        val doctorId = auth.currentUser?.uid ?: return emptyList()
        col.whereEqualTo("doctorId", doctorId).get().await()
            .documents.mapNotNull { it.toObject(Patient::class.java)?.copy(id = it.id) }
    } catch (_: Exception) { emptyList() }

    /** One-shot lookup of patients where this email is listed as a family/child contact. */
    suspend fun getPatientsByFamilyEmailOnce(email: String): List<Patient> = try {
        col.whereArrayContains("familyEmails", email.trim().lowercase()).get().await()
            .documents.mapNotNull { it.toObject(Patient::class.java)?.copy(id = it.id) }
    } catch (_: Exception) { emptyList() }

    /** Real-time list of patients this family/child email is linked to. */
    fun getPatientsByFamilyEmail(email: String): Flow<List<Patient>> = callbackFlow {
        val listener = col.whereArrayContains("familyEmails", email.trim().lowercase())
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(Patient::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    /** Real-time list of all patients admitted to the hospital (accessible to doctors) */
    fun getAllHospitalPatients(): Flow<List<Patient>> = callbackFlow {
        val listener = col.addSnapshotListener { snap, err ->
            if (err != null) { trySend(emptyList()); return@addSnapshotListener }
            val list = snap?.documents?.mapNotNull { doc ->
                doc.toObject(Patient::class.java)?.copy(id = doc.id)
            } ?: emptyList()
            trySend(list)
        }
        awaitClose { listener.remove() }
    }

    suspend fun addPatient(patient: Patient): Result<String> = try {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not authenticated"))
        val ref = kotlinx.coroutines.withTimeout(5000L) {
            col.add(patient.copy(doctorId = uid)).await()
        }
        Result.success(ref.id)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun updatePatient(patient: Patient): Result<Unit> = try {
        // Profile, BLE and camera writes must not overwrite the location being
        // updated concurrently by the phone's foreground service.
        col.document(patient.id).set(patient, SetOptions.mergeFields(
            "id", "name", "age", "gender", "condition", "roomNumber", "bloodGroup",
            "emergencyContact", "emergencyPhone", "doctorId", "doctorName", "doctorPhone",
            "doctorEmail", "hospitalName", "familyEmails", "bleDeviceAddress", "photoUrl",
            "admissionDate", "latestCameraFrame", "fallStatus", "fallStatusObservedAt"
        )).await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    /** Partial update on the existing patient document, covered by its access rules. */
    suspend fun updatePhoneLocation(patientId: String, location: PhoneLocation): Result<Unit> = runCatching {
        require(location.hasValidFix()) { "Invalid phone location" }
        col.document(patientId).update("phoneLocation", location).await()
    }

    suspend fun stopPhoneLocationSharing(patientId: String): Result<Unit> = runCatching {
        col.document(patientId).update(mapOf("phoneLocation.sharingActive" to false,
            "phoneLocation.locationAvailable" to false)).await()
    }

    suspend fun markPhoneLocationUnavailable(patientId: String): Result<Unit> = runCatching {
        col.document(patientId).update("phoneLocation.locationAvailable", false).await()
    }

    /** Updates only live fall telemetry. This avoids overwriting other patient
     * fields when camera and family/caregiver clients update concurrently. */
    suspend fun updateFallStatus(patientId: String, status: String, observedAt: Long): Result<Unit> = try {
        col.document(patientId).update(
            mapOf("fallStatus" to status, "fallStatusObservedAt" to observedAt)
        ).await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun deletePatient(id: String): Result<Unit> = try {
        col.document(id).delete().await()
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
}
