package com.geriatricare.app

import com.geriatricare.app.data.ml.ExactShapley
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelExplanationTest {
    @Test fun shapleyDistributesInteractionsAndIgnoresDummyFeature() {
        val result = ExactShapley.explain(doubleArrayOf(2.0, 3.0, 9.0), doubleArrayOf(0.0, 0.0, 0.0)) {
            5.0 + it[0] * it[1]
        }
        assertEquals(3.0, result[0], 1e-9)
        assertEquals(3.0, result[1], 1e-9)
        assertEquals(0.0, result[2], 1e-9)
        assertEquals(11.0, 5.0 + result.sum(), 1e-9)
    }

    @Test fun shapleyLinearEffectsUseReferenceDifference() {
        val result = ExactShapley.explain(doubleArrayOf(4.0, 2.0), doubleArrayOf(1.0, 3.0)) { 7.0 + 2 * it[0] - 3 * it[1] }
        assertEquals(6.0, result[0], 1e-9)
        assertEquals(3.0, result[1], 1e-9)
    }
}
