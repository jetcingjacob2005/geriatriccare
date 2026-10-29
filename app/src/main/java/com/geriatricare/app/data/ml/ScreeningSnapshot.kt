package com.geriatricare.app.data.ml

/** A transparent snapshot of the actual inputs used by the on-device screening
 * model. Values are intentionally nullable until a complete, valid feature set exists. */
data class ScreeningSnapshot(
    val score: Float? = null,
    val samplesCollected: Int = 0,
    val samplesRequired: Int = ScreeningReadiness.MIN_PREDICTION_SAMPLES,
    val status: String = "Waiting for sensor connection",
    val hrMean: Float? = null,
    val hrVariation: Float? = null,
    val spo2Mean: Float? = null,
    val spo2Variation: Float? = null,
    val tempMean: Float? = null,
    val tempVariation: Float? = null,
    val ecgBpm: Float? = null,
    val ecgRrVariation: Float? = null,
    /** Rule-based observations from the same live inputs. These are deliberately
     * shown as observations, not claimed as XGBoost/medical diagnoses. */
    val observedFactors: List<String> = emptyList(),
    val explanation: ModelExplanation? = null
)
