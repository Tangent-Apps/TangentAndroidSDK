package com.tangent.sdk

import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.tangent.sdk.paywall.SuperwallManager.Companion.didPurchase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallPurchaseDetectionTest {
    @Test
    fun aPurchaseCounts() {
        assertTrue(didPurchase(PaywallResult.Purchased("pack"), wasEntitled = false, isEntitled = false))
        // A pack bought on a subscribers-only paywall.
        assertTrue(didPurchase(PaywallResult.Purchased("pack"), wasEntitled = true, isEntitled = true))
    }

    @Test
    fun anEntitledUserClosingWithoutBuyingDoesNotCount() {
        assertFalse(didPurchase(PaywallResult.Declined(), wasEntitled = true, isEntitled = true))
    }

    @Test
    fun anEntitledUserRestoringDoesNotCount() {
        assertFalse(didPurchase(PaywallResult.Restored(), wasEntitled = true, isEntitled = true))
    }

    @Test
    fun aRestoreThatBringsAccessBackCounts() {
        assertTrue(didPurchase(PaywallResult.Restored(), wasEntitled = false, isEntitled = true))
        // Restored, then closed by the user (automatic dismissal off).
        assertTrue(didPurchase(PaywallResult.Declined(), wasEntitled = false, isEntitled = true))
    }

    @Test
    fun closingWithoutAccessDoesNotCount() {
        assertFalse(didPurchase(PaywallResult.Declined(), wasEntitled = false, isEntitled = false))
        assertFalse(didPurchase(PaywallResult.Restored(), wasEntitled = false, isEntitled = false))
    }
}
