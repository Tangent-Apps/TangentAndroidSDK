package com.tangent.sdk.adjust

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAttribution
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.LogLevel
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.tangent.sdk.internal.ActivityCallbacks
import com.tangent.sdk.internal.TangentLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Adjust attribution (iOS's `AdjustManager`): installs and sessions, events the app maps to Adjust tokens, and — with
 * [AdjustPaywallTracking] — paywall views and first purchases with revenue. Off (every call a no-op) until started
 * with an app token.
 */
class AdjustManager internal constructor() {
    /** Which Adjust environment events go to. `SANDBOX` shows the device in Adjust's Testing Console; sandbox traffic
     *  stays out of production reports and is not forwarded to partners. */
    enum class Environment(internal val value: String) {
        PRODUCTION(AdjustConfig.ENVIRONMENT_PRODUCTION),
        SANDBOX(AdjustConfig.ENVIRONMENT_SANDBOX)
    }

    // region Properties
    var isStarted = false
        private set
    private var paywallTracking: AdjustPaywallTracking? = null
    private var eventTokens: Map<String, String> = emptyMap()
    private val _adid = MutableStateFlow<String?>(null)
    /** Adjust's device ID, once Adjust has minted it (the first launch has to reach Adjust first). */
    val adid: StateFlow<String?> = _adid.asStateFlow()
    private val _attribution = MutableStateFlow<AdjustAttribution?>(null)
    /** The install's attribution (network, campaign, …), once Adjust has it. */
    val attribution: StateFlow<AdjustAttribution?> = _attribution.asStateFlow()
    private val adidListeners = CopyOnWriteArrayList<(String) -> Unit>()
    // endregion

    // region Setup
    internal fun start(
        application: Application,
        appToken: String,
        environment: Environment,
        isDebug: Boolean,
        paywallTracking: AdjustPaywallTracking?,
        eventTokens: Map<String, String>
    ) {
        if (isStarted) return
        this.paywallTracking = paywallTracking
        this.eventTokens = eventTokens
        val config = AdjustConfig(application, appToken.trim(), environment.value).apply {
            setLogLevel(if (isDebug) LogLevel.INFO else LogLevel.WARN)
            setOnSessionTrackingSucceededListener { session ->
                session.adid?.takeIf { it.isNotBlank() }?.let(::didGetAdid)
            }
            setOnAttributionChangedListener { attribution -> _attribution.value = attribution }
        }
        Adjust.initSdk(config)
        // Sessions follow the app coming and going.
        application.registerActivityLifecycleCallbacks(object : ActivityCallbacks() {
            override fun onActivityResumed(activity: Activity) = Adjust.onResume()
            override fun onActivityPaused(activity: Activity) = Adjust.onPause()
        })
        isStarted = true
        askForAdid()
        // Asked again once the SDK has surely finished starting (the first request can be dropped).
        Handler(Looper.getMainLooper()).postDelayed(::askForAdid, ADID_RETRY_DELAY_MILLIS)
        TangentLog.i("✅ Adjust: started (${environment.name.lowercase()})")
    }
    // endregion

    // region Device IDs
    /**
     * The IDs Superwall's Adjust integration matches its server-side events (renewals, refunds…) by: Adjust's device
     * ID (`adjustAdid`, now and whenever Adjust confirms a session) and Google's advertising ID (`advertisingId`) when
     * the user hasn't opted out of ad personalisation. Each is reported as soon as Adjust has it.
     */
    fun deviceIds(context: Context, onId: (key: String, value: String) -> Unit) {
        if (!isStarted) return
        onAdid { adid -> onId(ADJUST_ADID_ATTRIBUTE, adid) }
        Adjust.getGoogleAdId(context) { adId ->
            // Opted out: Google returns zeros.
            if (!adId.isNullOrBlank() && adId.any { it != '0' && it != '-' }) onId(ADVERTISING_ID_ATTRIBUTE, adId)
        }
    }

    /** Called with Adjust's device ID now if it's known, and again every time Adjust confirms it. */
    fun onAdid(listener: (String) -> Unit) {
        adidListeners += listener
        _adid.value?.let(listener)
    }

    private fun askForAdid() {
        Adjust.getAdid { adid -> if (!adid.isNullOrBlank()) didGetAdid(adid) }
    }

    private fun didGetAdid(adid: String) {
        _adid.value = adid
        adidListeners.forEach { it(adid) }
    }
    // endregion

    // region Events
    /**
     * An analytics event by name. Adjust only accepts events created in its dashboard, addressed by token, so a name
     * reaches Adjust only when it's in `AdjustConfiguration.eventTokens`; anything else is dropped (Adjust's backend
     * would discard it anyway). `AnalyticsService.track` forwards every event here.
     */
    fun trackEvent(name: String, parameters: Map<String, String> = emptyMap()) {
        if (!isStarted) return
        val token = eventTokens[name]?.takeIf { it.isNotBlank() } ?: return
        trackToken(token, parameters)
    }

    /** An event by its Adjust token, with callback parameters. */
    fun trackToken(token: String, parameters: Map<String, String> = emptyMap()) {
        if (!isStarted || token.isBlank()) return
        val event = AdjustEvent(token)
        parameters.forEach { (key, value) -> event.addCallbackParameter(key, value) }
        Adjust.trackEvent(event)
    }

    /** A paywall opened. Superwall's are reported by the SDK itself; call this for the app's own (local) paywalls. */
    fun paywallViewed() {
        val token = paywallTracking?.paywallViewedToken ?: return
        trackToken(token)
    }

    /**
     * A completed Google Play purchase: a free trial starting (no revenue), a paid subscription, or a one-time product.
     * Revenue is what's kept: the first paid phase's price times the take-home share. Called by `PlayBillingManager`
     * for every purchase it makes; call it yourself only for purchases made outside it.
     */
    fun purchased(details: ProductDetails, offerToken: String?, purchase: Purchase) {
        val tracking = paywallTracking ?: return
        if (details.productType == BillingClient.ProductType.SUBS) {
            val offer = details.subscriptionOfferDetails?.firstOrNull { it.offerToken == offerToken }
                ?: details.subscriptionOfferDetails?.firstOrNull()
            val firstPhase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull() ?: return
            if (firstPhase.priceAmountMicros == 0L) {
                trackPurchase(tracking.trialStartedToken, tracking, null, null, purchase, details.productId)
            } else {
                trackPurchase(
                    tracking.subscriptionStartedToken, tracking,
                    firstPhase.priceAmountMicros, firstPhase.priceCurrencyCode, purchase, details.productId
                )
            }
        } else {
            val price = details.oneTimePurchaseOfferDetails ?: return
            trackPurchase(
                tracking.oneTimePurchaseToken, tracking,
                price.priceAmountMicros, price.priceCurrencyCode, purchase, details.productId
            )
        }
    }

    private fun trackPurchase(
        token: String,
        tracking: AdjustPaywallTracking,
        priceMicros: Long?,
        currency: String?,
        purchase: Purchase,
        productId: String
    ) {
        if (!isStarted || token.isBlank()) return
        val event = AdjustEvent(token)
        if (priceMicros != null && currency != null) {
            event.setRevenue(priceMicros / 1_000_000.0 * tracking.share(), currency)
        }
        purchase.orderId?.let(event::setOrderId)
        event.setPurchaseToken(purchase.purchaseToken)
        event.setProductId(productId)
        Adjust.trackEvent(event)
        TangentLog.d("💰 Adjust: purchase $productId reported")
    }
    // endregion

    internal companion object {
        /** Superwall user attribute names its Adjust integration reads on Android. */
        const val ADJUST_ADID_ATTRIBUTE = "adjustAdid"
        const val ADVERTISING_ID_ATTRIBUTE = "advertisingId"
        const val ADID_RETRY_DELAY_MILLIS = 5_000L
    }
}
