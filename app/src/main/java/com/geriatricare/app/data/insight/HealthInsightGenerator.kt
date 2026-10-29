package com.geriatricare.app.data.insight

import com.geriatricare.app.data.model.BleData
import com.geriatricare.app.data.model.EcgAnalysis
import com.geriatricare.app.data.model.EcgStatus
import com.geriatricare.app.data.model.VitalLevel
import com.geriatricare.app.data.model.heartRateLevel
import com.geriatricare.app.data.model.spO2Level
import com.geriatricare.app.data.model.temperatureLevel

/**
 * Turns the raw numbers into a short, plain-language summary a family
 * member or non-medical caregiver can understand at a glance.
 *
 * This runs entirely on-device with simple rules — it is NOT a machine
 * learning model and NOT a medical diagnosis. It exists to translate
 * numbers into sentences, not to replace a doctor's judgement.
 *
 * Reference ranges used (standard resting adult ranges):
 *   Heart rate : 60–100 bpm
 *   SpO2       : 95–100% at sea level
 * (Same thresholds as the live alert banner — see spO2Level()/heartRateLevel()
 * in BleData.kt, so the two never drift out of sync with each other.)
 */
object HealthInsightGenerator {

    fun generate(vitals: BleData, ecg: EcgAnalysis): String {
        val parts = mutableListOf<String>()

        // ── Temperature ──────────────────────────────────────────────────────
        if (vitals.temp > 0) {
            parts += if (vitals.temp.temperatureLevel() == VitalLevel.CRITICAL) {
                "Body temperature is %.1f°C, which is above the normal range and may indicate a fever."
                    .format(vitals.temp)
            } else {
                "Body temperature is %.1f°C, which is within the normal range.".format(vitals.temp)
            }
        }

        // ── ECG rhythm ───────────────────────────────────────────────────────
        parts += when (ecg.status) {
            EcgStatus.LEAD_OFF   -> "The ECG electrode doesn't seem to be making good contact right now, so no reliable heart signal is being read."
            EcgStatus.FLATLINE   -> "The ECG isn't picking up a clear heart signal at the moment — this is usually a sensor placement issue, not a health event."
            EcgStatus.COLLECTING -> "Still gathering enough ECG data to say anything meaningful yet."
            EcgStatus.NOISY -> "The ECG signal is unreliable. Check electrode contact and wiring; no rhythm conclusion is available."
            EcgStatus.IRREGULAR  -> {
                val bpmText = ecg.estimatedBpm?.let { " (about $it bpm)" } ?: ""
                "The ECG rhythm looks irregular$bpmText — spacing between heartbeats is less steady than expected."
            }
            EcgStatus.NORMAL -> {
                val bpmText = ecg.estimatedBpm?.let { " at about $it bpm" } ?: ""
                "The ECG shows a steady, regular rhythm$bpmText."
            }
        }
        // ECG-derived rate flag (bradycardia/tachycardia), separate from rhythm regularity above.
        if (ecg.status == EcgStatus.NORMAL && ecg.rateLevel != null && ecg.rateLevel != VitalLevel.NORMAL) {
            val bpm = ecg.estimatedBpm
            parts += if (bpm != null && bpm < 60) {
                "That ECG-derived rate is below the typical 60–100 bpm resting range (bradycardia)."
            } else {
                "That ECG-derived rate is above the typical 60–100 bpm resting range (tachycardia)."
            }
        }

        // ── SpO2 / HR (once those sensors are wired up) ─────────────────────
        if (vitals.spo2 > 0) {
            parts += if (vitals.spo2.spO2Level() != VitalLevel.NORMAL) {
                "Oxygen saturation is ${vitals.spo2.toInt()}%, which is lower than the typical 95–100% healthy range."
            } else {
                "Oxygen saturation is ${vitals.spo2.toInt()}%, which is within the healthy range."
            }
        }
        if (vitals.hr > 0) {
            parts += if (vitals.hr.heartRateLevel() != VitalLevel.NORMAL) {
                "Heart rate is ${vitals.hr.toInt()} bpm, which is outside the typical 60–100 bpm resting range."
            } else {
                "Heart rate is ${vitals.hr.toInt()} bpm, within the typical 60–100 bpm resting range."
            }
        }

        // ── Overall takeaway ─────────────────────────────────────────────────
        val anyConcern = (vitals.temp > 0 && vitals.temp.temperatureLevel() == VitalLevel.CRITICAL) ||
            ecg.status == EcgStatus.IRREGULAR ||
            (ecg.rateLevel != null && ecg.rateLevel != VitalLevel.NORMAL) ||
            (vitals.spo2 > 0 && vitals.spo2.spO2Level() != VitalLevel.NORMAL) ||
            (vitals.hr > 0 && vitals.hr.heartRateLevel() != VitalLevel.NORMAL)

        parts += if (anyConcern) {
            "Overall: one or more readings are outside the usual range — consider mentioning this to a doctor, especially if it continues."
        } else {
            "Overall: readings currently look within normal ranges. This is a general summary, not a medical diagnosis."
        }

        return parts.joinToString(" ")
    }
}
