package com.tangent.sdk.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.tangent.sdk.internal.TangentLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * The one connection to Google Play Billing (Android's StoreKit): product prices, the purchase sheet, and the
 * purchases Google Play holds for this user. Every purchase update (including ones approved later, like a pending
 * payment) comes through [purchaseUpdates], and every purchase made with [purchase] is reported to Adjust once.
 */
class PlayBillingManager internal constructor() : PurchasesUpdatedListener {
    // region Properties
    private var client: BillingClient? = null
    private val connectLock = Mutex()
    private val _purchaseUpdates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    val purchaseUpdates: SharedFlow<PurchaseUpdate> = _purchaseUpdates.asSharedFlow()
    /** Told about each completed purchase made through [purchase] (the SDK reports it to Adjust). */
    internal var onPurchased: ((ProductDetails, String?, Purchase) -> Unit)? = null
    // endregion

    // region Setup
    internal fun start(context: Context) {
        if (client != null) return
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }
    // endregion

    // region Connecting
    /** Whether Google Play Billing is reachable (not on devices without the Play Store). One connection attempt at a
     *  time: callers at launch wait for the same one, since a second attempt while connecting is refused. */
    private suspend fun connect(): BillingClient? = connectLock.withLock {
        val client = client ?: return@withLock null
        if (client.isReady) return@withLock client
        val isConnected = suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
        if (isConnected) client else null
    }
    // endregion

    // region Products
    /** The products' details (prices, base plans); missing ones are left out. [productType] is a
     *  `BillingClient.ProductType`. */
    suspend fun productDetails(productIds: List<String>, productType: String): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()
        val client = connect() ?: return emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(productType).build()
                }
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            TangentLog.e("❌ Billing: products failed: ${result.billingResult.debugMessage}")
        }
        return result.productDetailsList.orEmpty()
    }

    suspend fun subscriptionDetails(productIds: List<String>): List<ProductDetails> =
        productDetails(productIds, BillingClient.ProductType.SUBS)

    suspend fun oneTimeProductDetails(productIds: List<String>): List<ProductDetails> =
        productDetails(productIds, BillingClient.ProductType.INAPP)
    // endregion

    // region Purchasing
    /**
     * Shows Google Play's purchase sheet and waits for its answer.
     *
     * @param offerToken picks a subscription's base plan / offer (`null` for one-time products).
     * @param obfuscatedAccountId stamps the purchase with the user's account ID (`AccountId`), which Google repeats in
     *   every later notification (renewal, cancel, refund): Superwall and the server match those to the user by it.
     */
    suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String? = null,
        obfuscatedAccountId: String? = null
    ): PurchaseUpdate {
        val client = connect() ?: return PurchaseUpdate(errorResult(), emptyList())
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply { offerToken?.let(::setOfferToken) }
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .apply { obfuscatedAccountId?.let(::setObfuscatedAccountId) }
            .build()
        return coroutineScope {
            // Listening before the sheet opens, so its answer can't be missed. Only an answer about this product (or an
            // error / cancel, which carry no purchases) counts: another purchase approved meanwhile isn't this one.
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                purchaseUpdates.first { update ->
                    !update.isOK || update.purchases.isEmpty() ||
                        update.purchases.any { productDetails.productId in it.products }
                }
            }
            val launch = client.launchBillingFlow(activity, params)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                answer.cancel()
                PurchaseUpdate(launch, emptyList())
            } else {
                answer.await().also { update ->
                    val purchase = update.purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    if (update.isOK && purchase != null) onPurchased?.invoke(productDetails, offerToken, purchase)
                }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        _purchaseUpdates.tryEmit(PurchaseUpdate(result, purchases.orEmpty()))
    }
    // endregion

    // region Owned purchases
    /** The purchases Google Play holds for this user: active subscriptions, or one-time products not consumed yet.
     *  `null` when Google Play couldn't be asked. */
    suspend fun purchases(productType: String): List<Purchase>? {
        val client = connect() ?: return null
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(productType).build())
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.purchasesList
    }

    suspend fun subscriptions(): List<Purchase>? = purchases(BillingClient.ProductType.SUBS)

    /** Google Play refunds a purchase after 3 days unless it's acknowledged (iOS's `transaction.finish()`). */
    suspend fun acknowledge(purchaseToken: String): Boolean {
        val client = connect() ?: return false
        val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build())
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** A consumable that's been credited: used up, so it can be bought again. */
    suspend fun consume(purchaseToken: String): Boolean {
        val client = connect() ?: return false
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build())
        return result.billingResult.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun errorResult(): BillingResult =
        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).build()
    // endregion
}
