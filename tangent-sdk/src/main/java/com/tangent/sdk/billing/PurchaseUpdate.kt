package com.tangent.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase

/** One answer from Google Play about purchases: the result code and the purchases it concerns. */
data class PurchaseUpdate(val result: BillingResult, val purchases: List<Purchase>) {
    val isOK: Boolean
        get() = result.responseCode == BillingResponseCode.OK
    val isCancelled: Boolean
        get() = result.responseCode == BillingResponseCode.USER_CANCELED
    /** Paid for (not pending, e.g. a cash payment still on its way). */
    val isPurchased: Boolean
        get() = isOK && purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    /** Google Play's message, or the response code's name when it sends none (e.g. ITEM_UNAVAILABLE). */
    val errorDescription: String
        get() = result.debugMessage.ifBlank { responseCodeName(result.responseCode) }

    internal companion object {
        fun responseCodeName(code: Int): String =
            when (code) {
                BillingResponseCode.OK -> "OK"
                BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
                BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
                BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
                BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
                BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
                BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
                BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
                BillingResponseCode.ERROR -> "ERROR"
                BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
                BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
                BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
                else -> "CODE_$code"
            }
    }
}
