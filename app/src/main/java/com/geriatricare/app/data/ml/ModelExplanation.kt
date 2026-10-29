package com.geriatricare.app.data.ml

data class FeatureContribution(val feature: String, val label: String, val value: Double, val logOdds: Double)
data class ModelExplanation(
    val referenceProbability: Double,
    val predictionProbability: Double,
    val contributions: List<FeatureContribution>,
    val reconstructionError: Double
)

/** Exact eight-feature Shapley enumeration relative to one training-only reference.
 * Interactions are distributed over all feature orders. This is not causal XAI,
 * and a single median reference is not a full population background distribution.
 */
object ExactShapley {
    fun explain(values: DoubleArray, reference: DoubleArray, predictMargin: (DoubleArray) -> Double): DoubleArray {
        require(values.size == reference.size && values.size in 1..10)
        val n = values.size
        val coalition = DoubleArray(1 shl n) { mask ->
            predictMargin(DoubleArray(n) { i -> if (mask and (1 shl i) != 0) values[i] else reference[i] })
        }
        fun choose(a: Int, b: Int): Double {
            var result = 1.0
            for (i in 1..b) result = result * (a - i + 1) / i
            return result
        }
        return DoubleArray(n) { i ->
            var contribution = 0.0
            for (mask in coalition.indices) {
                if (mask and (1 shl i) == 0) {
                    val count = Integer.bitCount(mask)
                    contribution += (coalition[mask or (1 shl i)] - coalition[mask]) / (n * choose(n - 1, count))
                }
            }
            contribution
        }
    }
}
