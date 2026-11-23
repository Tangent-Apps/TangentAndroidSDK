package com.tangent.sdk.paywall

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.tangent.sdk.billing.PlayBillingManager
import com.tangent.sdk.billing.SubscriptionManager

/**
 * Superwall shows the paywalls; the buying is ours, through `PlayBillingManager`, so a Superwall purchase reaches
 * `SubscriptionManager` and Adjust exactly like a local paywall one, stamped with the user's account ID.
 */
internal class TangentPurchaseController(
    private val billing: PlayBillingManager,
    private val subscriptions: SubscriptionManager,
    private val purchases: SuperwallManager.Purchases,
    /** The account ID to stamp a purchase with, waiting a little for a sign-in on its way; `null` without one. */
    private val accountId: suspend () -> String?
) : PurchaseController {
    // region Purchasing
    /** Superwall's newer `StoreProduct` overload hands its Google Play product down to this one, so every Play
     *  purchase lands here. */
    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?
    ): PurchaseResult =
        if (productDetails.productType == BillingClient.ProductType.SUBS) {
            purchaseSubscription(activity, productDetails, basePlanId, offerId)
        } else {
            purchaseOneTimeProduct(activity, productDetails)
        }

    private suspend fun purchaseSubscription(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?
    ): PurchaseResult {
        val offerToken = offerToken(productDetails, basePlanId, offerId)
            ?: return PurchaseResult.Failed("No offer for ${productDetails.productId}")
        val accountId = accountId()
        if (accountId == null && purchases.requireAccountId) return PurchaseResult.Failed("Not signed in yet.")
        val update = billing.purchase(activity, productDetails, offerToken, accountId)
        return when {
            update.isCancelled ->
                PurchaseResult.Cancelled()

            !update.isOK ->
                PurchaseResult.Failed(update.errorDescription)

            update.isPurchased -> {
                subscriptions.setSubscribed(true)
                PurchaseResult.Purchased()
            }

            // Paid later (e.g. cash): the subscription arrives once Google Play confirms it.
            else ->
                PurchaseResult.Pending()
        }
    }

    /** The app's own handler when it has one (e.g. packs credited by the server before they're consumed), else a
     *  plain Play purchase left for the app to consume / acknowledge from `purchaseUpdates`. */
    private suspend fun purchaseOneTimeProduct(activity: Activity, productDetails: ProductDetails): PurchaseResult {
        purchases.oneTimePurchase?.let { return it(activity, productDetails) }
        val accountId = accountId()
        if (accountId == null && purchases.requireAccountId) return PurchaseResult.Failed("Not signed in yet.")
        val update = billing.purchase(activity, productDetails, offerToken = null, obfuscatedAccountId = accountId)
        return when {
            update.isCancelled -> PurchaseResult.Cancelled()
            !update.isOK -> PurchaseResult.Failed(update.errorDescription)
            update.isPurchased -> PurchaseResult.Purchased()
            else -> PurchaseResult.Pending()
        }
    }
    // endregion

    // region Restoring
    /** "Restore" on a Superwall paywall: subscriptions from Google Play again, then the app's own restore. */
    override suspend fun restorePurchases(): RestorationResult {
        subscriptions.refresh()
        purchases.onRestore?.invoke()
        return RestorationResult.Restored()
    }
    // endregion

    internal companion object {
        /** The paywall's plan and offer, else the plan's base offer, else any. */
        fun offerToken(productDetails: ProductDetails, basePlanId: String?, offerId: String?): String? {
            val offers = productDetails.subscriptionOfferDetails.orEmpty()
            val offer = offers.firstOrNull { it.basePlanId == basePlanId && it.offerId == offerId }
                ?: offers.firstOrNull { it.basePlanId == basePlanId && it.offerId == null }
                ?: offers.firstOrNull()
            return offer?.offerToken
        }
    }
}
