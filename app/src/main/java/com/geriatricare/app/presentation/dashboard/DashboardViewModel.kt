package com.geriatricare.app.presentation.dashboard

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.bluetooth.BleManager
import com.geriatricare.app.data.ml.RiskClassifier
import com.geriatricare.app.data.ml.ScreeningSnapshot
import com.geriatricare.app.data.model.*
import com.geriatricare.app.data.report.ReportGenerator
import com.geriatricare.app.data.report.WhatsAppSender
import com.geriatricare.app.data.repository.PatientRepository
import com.geriatricare.app.data.repository.VitalRepository
import com.geriatricare.app.data.repository.AppointmentRepository
import com.geriatricare.app.data.repository.MedicationReminderRepository
import com.geriatricare.app.data.medication.MedicationReminderScheduler
import com.geriatricare.app.data.location.PhoneLocationSharing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.geriatricare.app.data.ml.FallDetector
import com.geriatricare.app.data.ml.FallStatus
import com.geriatricare.app.data.alert.AlertSoundPlayer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

data class DashboardUiState(
    val patient       : Patient? = null,
    val isLoading     : Boolean  = true,
    val alertMessage  : String   = "",
    val lastSavedTime : Long     = 0L,
    val autoReportSentTime: Long = 0L,
    val autoReportWarmupRemainingMs: Long = 0L
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val patientRepo  : PatientRepository,
    private val vitalRepo    : VitalRepository,
    private val scanResultRepo: com.geriatricare.app.data.repository.ScanResultRepository,
    private val alertManager : com.geriatricare.app.data.alert.EmergencyAlertManager,
    val bleManager           : BleManager,
    private val riskClassifier: RiskClassifier,
    private val appointmentRepo: AppointmentRepository,
    private val medicationReminderRepo: MedicationReminderRepository,
    private val medicationReminderScheduler: MedicationReminderScheduler,
    private val phoneLocationSharing: PhoneLocationSharing,
    @ApplicationContext private val appContext: Context,
    savedStateHandle         : SavedStateHandle
) : ViewModel() {

    val patientId: String = savedStateHandle["patientId"] ?: ""

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    val phoneLocation = patientRepo.getPatientFlow(patientId)
        .map { it?.phoneLocation }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val locationSharingState = phoneLocationSharing.state

    fun startPhoneLocationSharing() = phoneLocationSharing.start(patientId)
    fun stopPhoneLocationSharing() {
        if (locationSharingState.value.patientId == patientId) phoneLocationSharing.stop()
    }

    // ── BLE streams (always real sensor data — never replaced with generated values) ──
    val bleData        = bleManager.bleData
    val connectionState= bleManager.connectionState
    val ecgBuffer      = bleManager.ecgBuffer
    val scannedDevices = bleManager.scannedDevices
    val discoveryInfo = bleManager.discoveryInfo
    val scanMessage    = bleManager.scanMessage

    // 7-day early-deterioration trend analysis (personalized baseline + slope)
    val trendAnalysis: StateFlow<com.geriatricare.app.data.analytics.DeteriorationResult> =
        vitalRepo.getReadingsSince(patientId, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            .map { com.geriatricare.app.data.analytics.DeteriorationAnalyzer.analyze(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.geriatricare.app.data.analytics.DeteriorationResult(
                    com.geriatricare.app.data.analytics.RiskLevel.INSUFFICIENT_DATA, 0, emptyList(), ""
                )
            )

    // ── ML risk score (trained on real patient data — see /ml/train_risk_model.py) ──
    val riskModelAvailable: Boolean = riskClassifier.isAvailable

    // Produce an initial score from the first complete live feature set, then
    // progressively stabilize its statistics with up to 30 minutes of history.
    private val hrWindow = ArrayDeque<Float>(com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES)
    private val spo2Window = ArrayDeque<Float>(com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES)
    private val tempWindow = ArrayDeque<Float>(com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES)
    private val _mlRiskScore = MutableStateFlow<Float?>(null)
    val mlRiskScore: StateFlow<Float?> = _mlRiskScore
    private val _screeningSnapshot = MutableStateFlow(ScreeningSnapshot())
    val screeningSnapshot: StateFlow<ScreeningSnapshot> = _screeningSnapshot

    // Throttled periodic ECG analysis computed on Dispatchers.Default
    private val _ecgAnalysis = MutableStateFlow(EcgAnalysis(EcgStatus.COLLECTING, "Collecting ECG data…"))
    val ecgAnalysis: StateFlow<EcgAnalysis> = _ecgAnalysis

    // ── Appointments & Medications ──
    val appointments: StateFlow<List<Appointment>> =
        appointmentRepo.getForPatient(patientId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val todayDate: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val todayMedications: StateFlow<List<MedicationReminder>> =
        medicationReminderRepo.getTodayForPatient(patientId, todayDate)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Keep an on-device schedule for the full prescribed period, not merely
    // today's visible cards. This Firestore listener updates the schedule as
    // soon as the doctor adds, changes, or removes a reminder.
    private val medicationNotificationSchedule: StateFlow<List<MedicationReminder>> =
        medicationReminderRepo.getUpcomingForPatient(patientId, todayDate)
            .onEach { medicationReminderScheduler.sync(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun markMedicationTaken(reminderId: String) {
        medicationReminderScheduler.cancel(reminderId)
        viewModelScope.launch { medicationReminderRepo.markTaken(reminderId) }
    }

    fun markMedicationSkipped(reminderId: String) {
        medicationReminderScheduler.cancel(reminderId)
        viewModelScope.launch { medicationReminderRepo.markSkipped(reminderId) }
    }

    fun deleteMedicationReminder(reminderId: String) {
        medicationReminderScheduler.cancel(reminderId)
        viewModelScope.launch { medicationReminderRepo.delete(reminderId) }
    }

    fun deleteAppointment(appointmentId: String) {
        viewModelScope.launch { appointmentRepo.delete(appointmentId) }
    }

    // ── Real Home CCTV Camera & Fall Detection ──
    private val fallDetector = FallDetector()
    private val poseDetectorOptions = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()
    private val poseDetector by lazy {
        try {
            PoseDetection.getClient(poseDetectorOptions)
        } catch (_: Exception) {
            null
        }
    }

    private val _fallStatus = MutableStateFlow(FallStatus.NORMAL)
    val fallStatus: StateFlow<FallStatus> = _fallStatus

    private val _fallTimestamp = MutableStateFlow(0L)
    val fallTimestamp: StateFlow<Long> = _fallTimestamp

    private val _fallAlertExactTime = MutableStateFlow<String?>(null)
    val fallAlertExactTime: StateFlow<String?> = _fallAlertExactTime

    private val _personBoundingBox = MutableStateFlow<FloatArray?>(null)
    val personBoundingBox: StateFlow<FloatArray?> = _personBoundingBox

    private val _isCctvActive = MutableStateFlow(true)
    val isCctvActive: StateFlow<Boolean> = _isCctvActive
    private var lastFallStatusHeartbeatAt = 0L

    fun toggleCctv() {
        _isCctvActive.value = !_isCctvActive.value
    }

    fun dismissFallAlert() {
        fallDetector.reset()
        _fallStatus.value = FallStatus.NORMAL
        _fallTimestamp.value = 0L
        _fallAlertExactTime.value = null
        _personBoundingBox.value = null
        lastFallStatusHeartbeatAt = 0L
        viewModelScope.launch {
            _uiState.value.patient?.let { p ->
                patientRepo.updateFallStatus(p.id, FallStatus.NORMAL.name, System.currentTimeMillis())
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processCctvFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val detector = poseDetector
        if (detector == null || !_isCctvActive.value) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val rawWidth = imageProxy.width
            val rawHeight = imageProxy.height
            val poseFrameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
            val poseFrameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

            detector.process(image)
                .addOnSuccessListener { pose ->
                    val result = fallDetector.processPose(pose, poseFrameWidth, poseFrameHeight)
                    _personBoundingBox.value = result.boundingBox
                    val now = System.currentTimeMillis()

                    if (result.status != FallStatus.NORMAL) {
                        if (_fallTimestamp.value == 0L) {
                            val now = System.currentTimeMillis()
                            val elapsed = result.fallDurationMs
                            val detectedTime = if (elapsed > 0L) (now - elapsed) else now
                            _fallTimestamp.value = detectedTime
                            val exactFormatted = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(detectedTime))
                            _fallAlertExactTime.value = exactFormatted
                        }
                    }

                    if (result.status != _fallStatus.value && result.status != FallStatus.NORMAL) {
                        _fallStatus.value = result.status
                        val exactFormatted = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(now))
                        _fallAlertExactTime.value = exactFormatted
                        lastFallStatusHeartbeatAt = now

                        viewModelScope.launch {
                            val currentPatient = _uiState.value.patient
                            if (currentPatient != null) {
                                patientRepo.updateFallStatus(currentPatient.id, result.status.name, now)
                                alertManager.triggerFallAlert(
                                    patient = currentPatient,
                                    isUnconscious = (result.status == FallStatus.UNCONSCIOUS),
                                    fallDurationMs = result.fallDurationMs
                                )
                            }
                            AlertSoundPlayer.playUrgentAlert(appContext)
                        }
                    } else if (result.status != FallStatus.NORMAL && now - lastFallStatusHeartbeatAt >= FALL_STATUS_HEARTBEAT_MS) {
                        // Keep an active event live only while fresh camera frames
                        // continue to confirm it. This is not another alert.
                        lastFallStatusHeartbeatAt = now
                        viewModelScope.launch {
                            _uiState.value.patient?.let { p ->
                                patientRepo.updateFallStatus(p.id, result.status.name, now)
                            }
                        }
                    } else if (result.status == FallStatus.NORMAL) {
                        // Person recovered — auto-dismiss the fall alert
                        if (_fallStatus.value != FallStatus.NORMAL) {
                            _fallStatus.value = FallStatus.NORMAL
                            _fallTimestamp.value = 0L
                            _fallAlertExactTime.value = null
                            _personBoundingBox.value = null
                            lastFallStatusHeartbeatAt = 0L
                            viewModelScope.launch {
                                _uiState.value.patient?.let { p ->
                                    patientRepo.updateFallStatus(p.id, FallStatus.NORMAL.name, now)
                                }
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    // ── ML Risk + ECG Analysis ──
    private fun watchEcgAndRisk() {
        viewModelScope.launch(Dispatchers.Default) {
            var lastPredictionAt = 0L
            var lastSampledPacketAt = 0L
            while (isActive) {
                delay(1200L)
                val ecgList = ecgBuffer.value
                val isConnected = connectionState.value.isConnected
                val analysis = if (isConnected && ecgList.isNotEmpty()) {
                    ecgList.analyzeEcg(bleManager.ecgSampleRate.value)
                } else {
                    EcgAnalysis(EcgStatus.COLLECTING, if (isConnected) "Collecting ECG data…" else "Waiting for sensor connection…")
                }
                _ecgAnalysis.value = analysis

                if (riskModelAvailable && isConnected) {
                    val data = bleData.value
                    val packetAt = bleManager.lastPacketAt.value
                    val missing = com.geriatricare.app.data.ml.ScreeningReadiness.missingVitals(data)
                    val fresh = packetAt > 0 && System.currentTimeMillis() - packetAt in 0..10_000L
                    if (!fresh || missing.isNotEmpty()) {
                        if (!fresh) {
                            hrWindow.clear(); spo2Window.clear(); tempWindow.clear()
                        }
                        lastPredictionAt = 0L
                        _mlRiskScore.value = null
                        _screeningSnapshot.value = ScreeningSnapshot(
                            samplesCollected = hrWindow.size,
                            status = if (!fresh) "Sensor stream paused. Waiting for fresh Bluetooth readings."
                            else "No usable ${missing.joinToString(", ")} reading. Check sensor contact; invalid values are not analysed.")
                        continue
                    }
                    if (packetAt != lastSampledPacketAt) {
                        lastSampledPacketAt = packetAt
                        hrWindow.addLast(data.hr); if (hrWindow.size > com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES) hrWindow.removeFirst()
                        spo2Window.addLast(data.spo2); if (spo2Window.size > com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES) spo2Window.removeFirst()
                        tempWindow.addLast(data.temp); if (tempWindow.size > com.geriatricare.app.data.ml.ScreeningReadiness.MAX_HISTORY_SAMPLES) tempWindow.removeFirst()
                    }

                    val samplesCollected = minOf(hrWindow.size, spo2Window.size, tempWindow.size)
                    val waiting = com.geriatricare.app.data.ml.ScreeningReadiness.status(
                        samplesCollected, analysis.estimatedBpm, analysis.rrVariabilityRatio, analysis.message)
                    if (waiting != null) {
                        _mlRiskScore.value = null
                        lastPredictionAt = 0L
                        _screeningSnapshot.value = ScreeningSnapshot(
                            samplesCollected = samplesCollected,
                            status = waiting
                        )
                        continue
                    }

                    if (hrWindow.size >= com.geriatricare.app.data.ml.ScreeningReadiness.MIN_PREDICTION_SAMPLES &&
                        spo2Window.size >= com.geriatricare.app.data.ml.ScreeningReadiness.MIN_PREDICTION_SAMPLES &&
                        tempWindow.size >= com.geriatricare.app.data.ml.ScreeningReadiness.MIN_PREDICTION_SAMPLES &&
                        analysis.estimatedBpm != null && analysis.rrVariabilityRatio != null &&
                        System.currentTimeMillis() - lastPredictionAt >= 5_000L) {
                        val (hrM, hrS) = RiskClassifier.computeWindowStats(hrWindow.toList())
                        val (spo2M, spo2S) = RiskClassifier.computeWindowStats(spo2Window.toList())
                        val (tempM, tempS) = RiskClassifier.computeWindowStats(tempWindow.toList())
                        val ecgBpm = analysis.estimatedBpm.toFloat()
                        val ecgRrCv = analysis.rrVariabilityRatio.toFloat()

                        val prediction = riskClassifier.predict(hrM, hrS, spo2M, spo2S, tempM, tempS, ecgBpm, ecgRrCv)
                        if (prediction != null) {
                            val explanation = riskClassifier.explain(doubleArrayOf(hrM.toDouble(), hrS.toDouble(), spo2M.toDouble(), spo2S.toDouble(), tempM.toDouble(), tempS.toDouble(), ecgBpm.toDouble(), ecgRrCv.toDouble()))
                            lastPredictionAt = System.currentTimeMillis()
                            _mlRiskScore.value = prediction
                            _screeningSnapshot.value = ScreeningSnapshot(
                                score = prediction,
                                samplesCollected = samplesCollected,
                                status = com.geriatricare.app.data.ml.ScreeningReadiness.resultStatus(samplesCollected),
                                hrMean = hrM, hrVariation = hrS,
                                spo2Mean = spo2M, spo2Variation = spo2S,
                                tempMean = tempM, tempVariation = tempS,
                                ecgBpm = ecgBpm, ecgRrVariation = ecgRrCv,
                                observedFactors = observedScreeningFactors(hrM, hrS, spo2M, spo2S, tempM, tempS, ecgBpm, ecgRrCv),
                                explanation = explanation
                            )
                            // This population-based screening score is advisory. Emergency
                            // dispatch remains tied to the separately validated live vital
                            // thresholds in watchAlerts(), not a model probability alone.
                        } else {
                            _mlRiskScore.value = null
                            _screeningSnapshot.value = ScreeningSnapshot(status = "Model inference unavailable. Live vital checks remain separate.")
                        }
                    }
                } else if (!isConnected) {
                    lastPredictionAt = 0L
                    lastSampledPacketAt = 0L
                    _mlRiskScore.value = null
                    _screeningSnapshot.value = ScreeningSnapshot()
                    hrWindow.clear()
                    spo2Window.clear()
                    tempWindow.clear()
                }
            }
        }
    }

    /** Explains only measurements actually observed in the live model inputs.
     * These threshold observations are kept separate from model attribution. */
    private fun observedScreeningFactors(
        hrMean: Float, hrVariation: Float, spo2Mean: Float, spo2Variation: Float,
        tempMean: Float, tempVariation: Float, ecgBpm: Float, ecgRrVariation: Float
    ): List<String> = buildList {
        if (spo2Mean < 95f) add("Average SpO₂ was below the expected 95% range.")
        if (hrMean < 60f || hrMean > 100f) add("Average heart rate was outside the usual 60–100 bpm resting range.")
        if (tempMean < 36f || tempMean > 37.5f) add("Average temperature was outside the usual 36.0–37.5°C range.")
        if (hrVariation > 12f) add("Heart-rate readings showed increased variation in the available live readings.")
        if (spo2Variation > 2f) add("SpO₂ readings showed increased variation in the available live readings.")
        if (ecgBpm < 50f || ecgBpm > 110f) add("ECG-derived rate was outside the screening reference range.")
        if (ecgRrVariation > 0.15f) add("ECG beat-to-beat timing showed increased variation; review with clinical context.")
        if (isEmpty()) add("No out-of-range vital pattern was observed in the current available model inputs.")
    }

    // Guards the auto-WhatsApp-send so it fires once per connect/scan session
    private var autoReportSentForSession = false
    // Per-metric tracking for 2-minute continuous data validation
    private var ecgValidSinceMs: Long? = null
    private var hrValidSinceMs: Long? = null
    private var spo2ValidSinceMs: Long? = null
    private var tempValidSinceMs: Long? = null

    companion object {
        private const val AUTO_REPORT_WARMUP_MS = 120_000L // 2 minutes
        /** The active fall state is refreshed only while the station camera is
         * continuing to observe it. Family clients use this heartbeat to avoid
         * showing a stale event as a current emergency. */
        private const val FALL_STATUS_HEARTBEAT_MS = 10_000L
    }

    init {
        loadPatient()
        watchAlerts()
        scheduleSaveReadings()
        watchAutoReport()
        watchEcgAndRisk()
    }

    private fun loadPatient() {
        viewModelScope.launch {
            val p = patientRepo.getPatient(patientId)
            _uiState.update { it.copy(patient = p, isLoading = false) }
            p?.bleDeviceAddress?.takeIf { it.isNotEmpty() }?.let {
                bleManager.connectToAddress(it)
            }
        }
    }

    private fun watchAlerts() {
        viewModelScope.launch {
            bleData.collect { data ->
                val messages = buildList {
                    if (data.spo2 > 0 && data.spo2.spO2Level() != VitalLevel.NORMAL)
                        add("Low SpO₂ ${data.spo2.toInt()}%")
                    if (data.hr > 0 && data.hr.heartRateLevel() != VitalLevel.NORMAL)
                        add("Abnormal HR ${data.hr.toInt()} bpm")
                    if (data.temp > 0 && data.temp.temperatureLevel() != VitalLevel.NORMAL)
                        add("Abnormal Temp ${data.temp}°C")
                }
                val alertText = messages.joinToString("  •  ")
                _uiState.update { it.copy(alertMessage = alertText) }

                val isCritical = (data.spo2 > 0 && data.spo2 < 90f) ||
                        (data.hr > 0 && (data.hr < 40f || data.hr > 120f)) ||
                        (data.temp > 0 && (data.temp > 38.5f || data.temp < 35.0f))

                if (isCritical && messages.isNotEmpty()) {
                    val patient = _uiState.value.patient
                    if (patient != null) {
                        alertManager.triggerVitalAnomalyAlert(
                            patient = patient,
                            anomalySummary = alertText,
                            vitals = data
                        )
                    }
                }
            }
        }
    }

    private fun scheduleSaveReadings() {
        viewModelScope.launch {
            while (true) {
                delay(3_000L)
                val data = bleData.value  // Always use real BLE data
                if (data.spo2 > 0 || data.hr > 0 || data.temp > 0) {
                    val now = System.currentTimeMillis()
                    if (now - _uiState.value.lastSavedTime >= 15_000L) {
                        vitalRepo.save(
                            VitalReading(
                                patientId   = patientId,
                                timestamp   = now,
                                ecg         = data.ecg,
                                spO2        = data.spo2,
                                heartRate   = data.hr,
                                temperature = data.temp
                            )
                        )
                    scanResultRepo.save(
                        com.geriatricare.app.data.model.ScanResult(
                            patientId   = patientId,
                            timestamp   = now,
                            temp        = data.temp,
                            spo2        = data.spo2,
                            hr          = data.hr,
                            ecgStatus   = _ecgAnalysis.value.status.name,
                            ecgMessage  = _ecgAnalysis.value.message,
                            ecgBpm      = _ecgAnalysis.value.estimatedBpm,
                            insightText = "Continuous monitoring: SpO2 ${data.spo2.toInt()}%, HR ${data.hr.toInt()} bpm, Temp ${data.temp}°C"
                        )
                    )
                        _uiState.update { it.copy(lastSavedTime = now) }
                    }
                }
            }
        }
    }

    /**
     * Enhanced 2-minute auto-report: requires ALL FOUR metrics (ECG, HR, SpO₂, Temp)
     * to be continuously valid for ≥2 minutes. If any stream drops to 0 or
     * connection is lost, the timer resets. Fires once per session.
     */
    private fun watchAutoReport() {
        viewModelScope.launch {
            connectionState
                .combine(bleData) { conn, data -> conn to data }
                .collect { (conn, data) ->
                    if (!conn.isConnected) {
                        autoReportSentForSession = false
                        resetValidTimers()
                        _uiState.update { it.copy(autoReportWarmupRemainingMs = 0L) }
                        return@collect
                    }

                    val now = System.currentTimeMillis()

                    // Track each metric independently — reset if it goes to 0
                    if (data.ecg > 0) { if (ecgValidSinceMs == null) ecgValidSinceMs = now }
                    else ecgValidSinceMs = null

                    if (data.hr > 0f) { if (hrValidSinceMs == null) hrValidSinceMs = now }
                    else hrValidSinceMs = null

                    if (data.spo2 > 0f) { if (spo2ValidSinceMs == null) spo2ValidSinceMs = now }
                    else spo2ValidSinceMs = null

                    if (data.temp > 0f) { if (tempValidSinceMs == null) tempValidSinceMs = now }
                    else tempValidSinceMs = null

                    // All four must have a valid start time
                    val allValid = ecgValidSinceMs != null && hrValidSinceMs != null &&
                            spo2ValidSinceMs != null && tempValidSinceMs != null
                    if (!allValid) {
                        _uiState.update { it.copy(autoReportWarmupRemainingMs = AUTO_REPORT_WARMUP_MS) }
                        return@collect
                    }

                    // Find the latest start (the metric that started streaming most recently)
                    val latestStart = maxOf(ecgValidSinceMs!!, hrValidSinceMs!!, spo2ValidSinceMs!!, tempValidSinceMs!!)
                    val elapsed = now - latestStart
                    val remaining = (AUTO_REPORT_WARMUP_MS - elapsed).coerceAtLeast(0L)
                    _uiState.update { it.copy(autoReportWarmupRemainingMs = remaining) }

                    if (remaining > 0L || autoReportSentForSession) return@collect

                    val patient = _uiState.value.patient ?: return@collect
                    if (patient.emergencyPhone.isBlank()) return@collect

                    autoReportSentForSession = true
                    val ecgAnalysis = ecgBuffer.value.analyzeEcg(bleManager.ecgSampleRate.value)
                    val file = ReportGenerator.generate(
                        context = appContext,
                        patient = patient,
                        vitals  = data,
                        ecg     = ecgAnalysis
                    )
                    WhatsAppSender.sendReportToBystander(
                        context        = appContext,
                        bystanderName  = patient.emergencyContact,
                        bystanderPhone = patient.emergencyPhone,
                        patientName    = patient.name,
                        reportFile     = file
                    )
                    scanResultRepo.save(
                        com.geriatricare.app.data.model.ScanResult(
                            patientId   = patientId,
                            temp        = data.temp,
                            spo2        = data.spo2,
                            hr          = data.hr,
                            ecgStatus   = ecgAnalysis.status.name,
                            ecgMessage  = ecgAnalysis.message,
                            ecgBpm      = ecgAnalysis.estimatedBpm,
                            insightText = com.geriatricare.app.data.insight.HealthInsightGenerator.generate(data, ecgAnalysis)
                        )
                    )
                    _uiState.update { it.copy(autoReportSentTime = System.currentTimeMillis()) }
                }
        }
    }

    private fun resetValidTimers() {
        ecgValidSinceMs = null
        hrValidSinceMs = null
        spo2ValidSinceMs = null
        tempValidSinceMs = null
    }

    // ── BLE Actions ──────────────────────────────────────────────────────────
    fun startScan()  = bleManager.startScan()
    fun stopScan()   = bleManager.stopScan()
    fun reportBlePermissionsDenied() = bleManager.reportMissingScanPermission()
    fun connect(device: android.bluetooth.BluetoothDevice) {
        bleManager.connect(device)
        viewModelScope.launch {
            _uiState.value.patient?.let { p ->
                patientRepo.updatePatient(p.copy(bleDeviceAddress = device.address))
                _uiState.update { it.copy(patient = p.copy(bleDeviceAddress = device.address)) }
            }
        }
    }
    fun disconnect() = bleManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        try { poseDetector?.close() } catch (_: Exception) {}
        bleManager.disconnect()
    }
}
