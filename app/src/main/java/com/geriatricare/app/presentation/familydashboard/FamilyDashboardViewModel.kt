package com.geriatricare.app.presentation.familydashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geriatricare.app.data.analytics.DeteriorationAnalyzer
import com.geriatricare.app.data.analytics.DeteriorationResult
import com.geriatricare.app.data.analytics.RiskLevel
import com.geriatricare.app.data.model.Patient
import com.geriatricare.app.data.model.ScanResult
import com.geriatricare.app.data.model.VitalReading
import com.geriatricare.app.data.repository.PatientRepository
import com.geriatricare.app.data.repository.ScanResultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FamilyDashboardViewModel @Inject constructor(
    private val patientRepo    : PatientRepository,
    private val scanResultRepo : ScanResultRepository,
    savedStateHandle           : SavedStateHandle
) : ViewModel() {

    val patientId: String = savedStateHandle["patientId"] ?: ""

    val patient: StateFlow<Patient?> = patientRepo.getPatientFlow(patientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val scanResults: StateFlow<List<ScanResult>> = scanResultRepo.getRecent(patientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trendAnalysis: StateFlow<DeteriorationResult> = scanResults
        .map { results -> DeteriorationAnalyzer.analyze(results.map { it.toVitalReading() }) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            DeteriorationResult(RiskLevel.INSUFFICIENT_DATA, 0, emptyList(), "")
        )

    private fun ScanResult.toVitalReading() = VitalReading(
        patientId = patientId, timestamp = timestamp, ecg = 0,
        spO2 = spo2, heartRate = hr, temperature = temp
    )
}
