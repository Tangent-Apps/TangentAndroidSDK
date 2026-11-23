package com.tangent.sdk.paywall

import android.app.Activity
import android.app.Application
import android.net.Uri
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.identity.identify
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.PaywallPresentationHandler
import com.superwall.sdk.paywall.presentation.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.register
import com.tangent.sdk.adjust.AdjustManager
import com.tangent.sdk.billing.PlayBillingManager
import com.tangent.sdk.billing.SubscriptionManager
import com.tangent.sdk.internal.TangentLog
import com.tangent.sdk.paywall.screen.PaywallScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Superwall (iOS's `SuperwallManager`): configured with the SDK's own purchase controller, told whether the user is
 * entitled, identified by the app's account ID (with the Adjust device IDs its server integration needs), and every
 * paywall it opens reported to Adjust. Off (calls are no-ops, [present] says "not shown") without an API key.
 */
class SuperwallManager internal constructor(
    private val scope: CoroutineScope,
    private val billing: PlayBillingManager,
    private val subscriptions: SubscriptionManager,
    private val adjust: AdjustManager
) {
    /**
     * @param apiKey Superwall's Android public key.
     * @param entitlements what an active subscription grants (Superwall dashboard → Products → Entitlements).
     * @param passIdentifiersToPlayStore purchases carry the user's ID as is, which Google repeats on every renewal.
     * @param purchases how Superwall's purchases are made (see [Purchases]).
     * @param paywallScreen fixes for Superwall's paywall screen (see [PaywallScreen.Options]).
     * @param delegate the app's own Superwall delegate; every callback is forwarded to it.
     * @param options anything else on `SuperwallOptions`.
     */
    data class Configuration(
        val apiKey: String,
        val entitlements: Set<String> = setOf("pro"),
        val passIdentifiersToPlayStore: Boolean = true,
        val purchases: Purchases = Purchases(),
        val paywallScreen: PaywallScreen.Options = PaywallScreen.Options(),
        val delegate: SuperwallDelegate? = null,
        val options: SuperwallOptions.() -> Unit = {}
    )

    /**
     * @param requireAccountId refuse a purchase when no account ID is known in time, since an unstamped purchase could
     *   be claimed by any account (when the server grants by account). Off: it goes ahead unstamped.
     * @param oneTimePurchase buys a one-time product (e.g. a pack the server credits before it's consumed); without
     *   it, one-time products are bought plainly and left for the app to consume.
     * @param onRestore the app's part of "Restore" (subscriptions are re-read from Google Play first).
     */
    data class Purchases(
        val requireAccountId: Boolean = false,
        val oneTimePurchase: (suspend (Activity, ProductDetails) -> PurchaseResult)? = null,
        val onRestore: (suspend () -> Unit)? = null
    )

    // region Properties
    private var application: Application? = null
    /** Configured with a key: its paywalls can be shown. */
    val isConfigured: Boolean
        get() = Superwall.initialized
    private val _customActions = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Custom actions sent by paywall elements (set in Superwall's editor), on the main thread. */
    val customActions: SharedFlow<String> = _customActions.asSharedFlow()
    private val _paywallDismissed = MutableSharedFlow<PaywallInfo>(extraBufferCapacity = 8)
    /** Every paywall closing, however it was presented. */
    val paywallDismissed: SharedFlow<PaywallInfo> = _paywallDismissed.asSharedFlow()
    private val _redemptions = MutableSharedFlow<RedemptionResult>(extraBufferCapacity = 4)
    /** The result of redeeming a web-checkout (Stripe) link. */
    val redemptions: SharedFlow<RedemptionResult> = _redemptions.asSharedFlow()
    /** The paywall last seen or bought on (see [PaywallAttribution]). */
    @Volatile
    var lastPaywallAttribution: PaywallAttribution? = null
        private set
    private var appDelegate: SuperwallDelegate? = null
    // endregion

    // region Setup
    internal fun configure(application: Application, configuration: Configuration, accountId: suspend () -> String?) {
        if (isConfigured) return
        this.application = application
        appDelegate = configuration.delegate
        val purchaseController = TangentPurchaseController(billing, subscriptions, configuration.purchases, accountId)
        Superwall.configure(
            applicationContext = application,
            apiKey = configuration.apiKey,
            purchaseController = purchaseController,
            options = SuperwallOptions().apply {
                passIdentifiersToPlayStore = configuration.passIdentifiersToPlayStore
                configuration.options(this)
            }
        )
        Superwall.instance.delegate = delegate
        PaywallScreen.install(application, configuration.paywallScreen)
        syncSubscriptionStatus(configuration.entitlements)
        TangentLog.i("✅ Superwall: configured")
    }

    /** With our own purchase controller Superwall doesn't work out the status itself. */
    private fun syncSubscriptionStatus(entitlements: Set<String>) {
        val granted = entitlements.map(::Entitlement).toSet()
        scope.launch {
            subscriptions.isEntitled.collect { isEntitled ->
                Superwall.instance.setSubscriptionStatus(
                    if (isEntitled && granted.isNotEmpty()) SubscriptionStatus.Active(granted) else SubscriptionStatus.Inactive
                )
            }
        }
    }
    // endregion

    // region Identity
    /**
     * Superwall knows the user by [userId] (the app's account ID, also on their Google Play purchases), so its server
     * can match webhooks and renewals to them. Then shares the Adjust device IDs its Adjust integration matches by:
     * without them it skips the event ("Missing device IDs").
     */
    fun identify(userId: String) {
        if (!isConfigured) return
        Superwall.instance.identify(userId)
        shareAdjustIds()
    }

    /** Forgets the user (e.g. after deleting the account); identify the new one after. */
    fun reset() {
        if (!isConfigured) return
        Superwall.instance.reset()
    }

    fun setUserAttributes(attributes: Map<String, Any?>) {
        if (!isConfigured) return
        Superwall.instance.setUserAttributes(attributes)
    }

    private fun shareAdjustIds() {
        val context = application ?: return
        adjust.deviceIds(context) { key, value ->
            Superwall.instance.setUserAttributes(mapOf(key to value))
            if (key == AdjustManager.ADJUST_ADID_ATTRIBUTE) {
                Superwall.instance.setIntegrationAttributes(mapOf(AttributionProvider.ADJUST_ID to value))
            }
        }
    }
    // endregion

    // region Presenting
    /**
     * Shows the paywall for [placement] (iOS's `showPaywall`). Callbacks arrive on the main thread:
     * - [onPresent] once it's on screen;
     * - [onFinish] when it closes, with whether the user bought something on it or restored into access they didn't
     *   have when it opened (see [didPurchase]);
     * - [onNotShown] when no paywall showed (no campaign matched, holdout, already subscribed, or an error), so the
     *   caller can carry on or fall back to its local paywall instead of a dead button.
     */
    fun present(
        placement: String = DEFAULT_PLACEMENT,
        params: Map<String, Any> = emptyMap(),
        onPresent: (PaywallInfo) -> Unit = {},
        onFinish: (didPurchase: Boolean) -> Unit = {},
        onNotShown: (reason: Throwable) -> Unit = {}
    ) {
        if (!isConfigured) {
            onNotShown(IllegalStateException("Superwall isn't configured"))
            return
        }
        // Paywalls shown only to entitled users (e.g. packs for subscribers) close with access either way, so access
        // alone can't mean a purchase: only access gained while this paywall was open counts.
        val wasEntitled = subscriptions.isEntitled.value
        val handler = PaywallPresentationHandler().apply {
            onPresent { info -> onMain { onPresent(info) } }
            onDismiss { _, result ->
                onMain { onFinish(didPurchase(result, wasEntitled, subscriptions.isEntitled.value)) }
            }
            onSkip { reason -> onMain { onNotShown(reason) } }
            onError { error -> onMain { onNotShown(error) } }
        }
        Superwall.instance.register(placement, params, handler)
    }

    /** Closes the paywall on screen (e.g. to show something of the app's over it). */
    suspend fun dismiss() {
        if (!isConfigured) return
        withContext(Dispatchers.Main) { Superwall.instance.dismiss() }
    }

    /** A web-checkout (Stripe) return link or a Superwall preview link; `true` when Superwall handled it. */
    fun handleDeepLink(uri: Uri): Boolean =
        isConfigured && Superwall.instance.handleDeepLink(uri).getOrDefault(false)

    private fun onMain(action: () -> Unit) {
        scope.launch(Dispatchers.Main) { action() }
    }
    // endregion

    // region Delegate
    private val delegate = object : SuperwallDelegate {
        override fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
            when (val event = eventInfo.event) {
                is SuperwallEvent.PaywallOpen -> {
                    lastPaywallAttribution = PaywallAttribution.from(event.paywallInfo, productId = null, didConvert = false)
                    adjust.paywallViewed()
                }

                is SuperwallEvent.PaywallClose ->
                    _paywallDismissed.tryEmit(event.paywallInfo)

                is SuperwallEvent.TransactionComplete ->
                    lastPaywallAttribution = PaywallAttribution.from(
                        event.paywallInfo, productId = event.product.productIdentifier, didConvert = true
                    )

                else -> Unit
            }
            appDelegate?.handleSuperwallEvent(eventInfo)
        }

        override fun handleCustomPaywallAction(withName: String) {
            scope.launch(Dispatchers.Main) { _customActions.emit(withName) }
            appDelegate?.handleCustomPaywallAction(withName)
        }

        override fun subscriptionStatusDidChange(from: SubscriptionStatus, to: SubscriptionStatus) {
            appDelegate?.subscriptionStatusDidChange(from, to)
        }

        override fun willRedeemLink() {
            appDelegate?.willRedeemLink()
        }

        override fun didRedeemLink(result: RedemptionResult) {
            _redemptions.tryEmit(result)
            appDelegate?.didRedeemLink(result)
        }

        override fun willPresentPaywall(withInfo: PaywallInfo) {
            appDelegate?.willPresentPaywall(withInfo)
        }

        override fun didPresentPaywall(withInfo: PaywallInfo) {
            appDelegate?.didPresentPaywall(withInfo)
        }

        override fun willDismissPaywall(withInfo: PaywallInfo) {
            appDelegate?.willDismissPaywall(withInfo)
        }

        override fun didDismissPaywall(withInfo: PaywallInfo) {
            appDelegate?.didDismissPaywall(withInfo)
        }

        override fun paywallWillOpenURL(url: java.net.URI) {
            appDelegate?.paywallWillOpenURL(url)
        }

        override fun paywallWillOpenDeepLink(url: Uri) {
            appDelegate?.paywallWillOpenDeepLink(url)
        }

        override fun userAttributesDidChange(newAttributes: Map<String, Any>) {
            appDelegate?.userAttributesDidChange(newAttributes)
        }

        override fun customerInfoDidChange(from: CustomerInfo, to: CustomerInfo) {
            appDelegate?.customerInfoDidChange(from, to)
        }

        override fun handleLog(level: String, scope: String, message: String?, info: Map<String, Any>?, error: Throwable?) {
            appDelegate?.handleLog(level, scope, message, info, error)
        }
    }
    // endregion

    companion object {
        /** The placement iOS's `showPaywall()` uses by default. */
        const val DEFAULT_PLACEMENT = "campaign_trigger"

        /**
         * Whether a closed paywall counts as a purchase: something was bought on it, or the user came away entitled
         * after opening it without access (a Restore; with automatic dismissal off it closes as Declined).
         *
         * An entitled user who closes without buying isn't counted, even with Restore tapped. One rare miss: a cold
         * launch that still remembers "not subscribed" when Google Play's answer lands during the paywall counts its
         * close as a purchase.
         */
        internal fun didPurchase(result: PaywallResult, wasEntitled: Boolean, isEntitled: Boolean): Boolean =
            result is PaywallResult.Purchased || (!wasEntitled && isEntitled)
    }
}
