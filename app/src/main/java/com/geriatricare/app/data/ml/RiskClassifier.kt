package com.geriatricare.app.data.ml

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/** Offline evaluator for the exported XGBoost four-sensor screening model. */
@Singleton
class RiskClassifier @Inject constructor(@ApplicationContext context: Context) {
    private val trees: JSONArray?
    private val threshold: Float
    private val explanationReference: DoubleArray?

    val isAvailable: Boolean get() = trees != null

    init {
        var loadedTrees: JSONArray? = null
        var loadedThreshold = 0.5f
        try {
            loadedTrees = JSONArray(context.assets.open("tabular_risk_tree_dump.json").bufferedReader().readText())
            val metrics = JSONObject(context.assets.open("tabular_risk_metrics.json").bufferedReader().readText())
            loadedThreshold = metrics.optDouble("threshold", 0.5).toFloat()
            Log.i("RiskClassifier", "Loaded ${loadedTrees.length()} XGBoost screening trees")
        } catch (error: Exception) {
            Log.w("RiskClassifier", "XGBoost risk model unavailable: ${error.message}")
        }
        trees = loadedTrees
        threshold = loadedThreshold
        explanationReference = runCatching {
            val reference = JSONObject(context.assets.open("screening_explanation_reference.json").bufferedReader().readText()).getJSONObject("reference")
            DoubleArray(FEATURE_NAMES.size) { reference.getDouble(FEATURE_NAMES[it]) }
        }.getOrNull()
    }

    /** Validation threshold for advisory review only; never an emergency trigger on its own. */
    fun requiresReview(probability: Float): Boolean = probability >= threshold

    /**
     * Returns the VitalDB adverse-outcome screening probability. This model was
     * trained on intra-operative data and is not a diagnostic system.
     */
    fun predict(
        hrMean: Float, hrStd: Float, spo2Mean: Float, spo2Std: Float,
        tempMean: Float, tempStd: Float, ecgBpm: Float, ecgRrCv: Float
    ): Float? {
        val ensemble = trees ?: return null
        val values = mapOf(
            "hr_mean" to hrMean.toDouble(), "hr_std" to hrStd.toDouble(),
            "spo2_mean" to spo2Mean.toDouble(), "spo2_std" to spo2Std.toDouble(),
            "temp_mean" to tempMean.toDouble(), "temp_std" to tempStd.toDouble(),
            "ecg_bpm" to ecgBpm.toDouble(), "ecg_rr_cv" to ecgRrCv.toDouble()
        )
        if (values.values.any { !it.isFinite() }) return null
        return try {
            var logOdds = 0.0 // XGBoost binary base score 0.5 has logit 0.
            for (index in 0 until ensemble.length()) logOdds += evaluateNode(ensemble.getJSONObject(index), values)
            (1.0 / (1.0 + exp(-logOdds))).toFloat().coerceIn(0f, 1f)
        } catch (error: Exception) {
            Log.w("RiskClassifier", "XGBoost inference failed: ${error.message}")
            null
        }
    }

    private fun evaluateNode(node: JSONObject, values: Map<String, Double>): Double {
        if (node.has("leaf")) return node.getDouble("leaf")
        val feature = node.getString("split")
        // Native XGBoost compares float32 features and thresholds. Comparing the
        // rounded JSON threshold as a Double changes branches at boundary values.
        val branch = if ((values[feature] ?: Double.NaN).toFloat() < node.getDouble("split_condition").toFloat()) {
            node.getInt("yes")
        } else node.getInt("no")
        val children = node.getJSONArray("children")
        for (index in 0 until children.length()) {
            val child = children.getJSONObject(index)
            if (child.getInt("nodeid") == branch) return evaluateNode(child, values)
        }
        throw IllegalStateException("Missing XGBoost child node $branch")
    }

    /** Compute off the main thread, on the same inputs as the displayed prediction. */
    fun explain(values: DoubleArray): ModelExplanation? = runCatching {
        val ensemble = trees ?: return null
        val reference = explanationReference ?: return null
        require(values.size == FEATURE_NAMES.size && values.all { it.isFinite() })
        fun margin(input: DoubleArray): Double {
            val features = FEATURE_NAMES.mapIndexed { index, name -> name to input[index] }.toMap()
            var result = 0.0
            for (i in 0 until ensemble.length()) result += evaluateNode(ensemble.getJSONObject(i), features)
            return result
        }
        val contributions = ExactShapley.explain(values, reference, ::margin)
        val baseline = margin(reference)
        val prediction = margin(values)
        val error = kotlin.math.abs(baseline + contributions.sum() - prediction)
        require(error < 1e-6) { "Attribution reconstruction failed" }
        ModelExplanation(1.0 / (1.0 + exp(-baseline)), 1.0 / (1.0 + exp(-prediction)),
            FEATURE_NAMES.indices.map { FeatureContribution(FEATURE_NAMES[it], FEATURE_LABELS[it], values[it], contributions[it]) }
                .sortedByDescending { kotlin.math.abs(it.logOdds) }, error)
    }.getOrNull()

    companion object {
        val FEATURE_NAMES = listOf("hr_mean", "hr_std", "spo2_mean", "spo2_std", "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv")
        private val FEATURE_LABELS = listOf("Average heart rate", "Heart-rate variation", "Average SpO₂", "SpO₂ variation", "Average temperature", "Temperature variation", "ECG-derived heart rate", "ECG beat-timing variation")
        fun computeWindowStats(values: List<Float>): Pair<Float, Float> {
            val valid = values.filter { it.isFinite() && it > 0f }
            if (valid.isEmpty()) return 0f to 0f
            val mean = valid.average().toFloat()
            val variance = valid.sumOf { ((it - mean) * (it - mean)).toDouble() } / valid.size
            return mean to sqrt(variance).toFloat()
        }
    }
}
