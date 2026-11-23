package com.tangent.sdk

import com.tangent.sdk.adjust.AdjustPaywallTracking
import org.junit.Assert.assertEquals
import org.junit.Test

class AdjustPaywallTrackingTest {
    private fun tracking(share: Double?) = AdjustPaywallTracking("a", "b", "c", "d") { share }

    @Test
    fun usesTheDefaultWithoutAValue() {
        assertEquals(AdjustPaywallTracking.DEFAULT_TAKE_HOME_SHARE, tracking(null).share(), 0.0)
    }

    @Test
    fun usesAValidValue() {
        assertEquals(0.7, tracking(0.7).share(), 0.0)
    }

    @Test
    fun ignoresValuesThatCantBeAShare() {
        listOf(85.0, 0.0, -0.2, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertEquals(AdjustPaywallTracking.DEFAULT_TAKE_HOME_SHARE, tracking(value).share(), 0.0)
        }
    }
}
