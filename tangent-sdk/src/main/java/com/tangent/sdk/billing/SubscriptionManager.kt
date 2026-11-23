package com.tangent.sdk.billing

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.Purchase
import com.tangent.sdk.internal.SdkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the user is subscribed, from Google Play (iOS's Superwall `isSubscribed`; with our own purchase controller
 * Superwall doesn't work it out itself, so it's told from here). Remembered, so the app opens on the right screen at
 * once, then checked with Google Play at launch, on returning to the app, after every purchase and on an interval while
 * the app is open.
 */
class SubscriptionManager internal constructor(
    private val scope: CoroutineScope,
    private val billing: PlayBillingManager
) {
    /**
     * @param productIds the subscriptions that grant access (e.g. `com.tangent.app.premium`); empty = any subscription.
     * @param acknowledge acknowledge active subscriptions here. Turn off when the server acknowledges them once it has
     *   verified the purchase (Google Play refunds unacknowledged ones after 3 days; see [hasUnacknowledgedSubscription]).
     * @param refreshIntervalMillis how often to ask Google Play again while the app is open; `null` = only at launch,
     *   on returning and after purchases.
     * @param rememberStatus start from the last answer (until Google Play answers again).
     * @param forceSubscribed always subscribed (debug builds, to test premium screens).
     */
    data class Configuration(
        val productIds: Set<String> = emptySet(),
        val acknowledge: Boolean = true,
        val refreshIntervalMillis: Long? = 300_000L,
        val rememberStatus: Boolean = true,
        val forceSubscribed: Boolean = false
    )

    // region Properties
    private var configuration = Configuration()
    private var preferences: SdkPreferences? = null
    private val _isSubscribed = MutableStateFlow(false)
    /** A Google Play subscription is active on this account (or [Configuration.forceSubscribed]). */
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()
    private val _hasExternalEntitlement = MutableStateFlow(false)
    /** Access granted outside Google Play, as the app's server says (e.g. a redeemed review code, an iOS purchase). */
    val hasExternalEntitlement: StateFlow<Boolean> = _hasExternalEntitlement.asStateFlow()
    private val _isEntitled = MutableStateFlow(false)
    /** [isSubscribed] or [hasExternalEntitlement]: what Superwall is told. */
    val isEntitled: StateFlow<Boolean> = _isEntitled.asStateFlow()
    /** An active subscription the server hasn't acknowledged yet (with [Configuration.acknowledge] off): sync it to the
     *  server again before Google Play refunds it. Updated by [refresh]. */
    var hasUnacknowledgedSubscription = false
        private set
    // endregion

    // region Setup
    internal fun start(configuration: Configuration, preferences: SdkPreferences) {
        this.configuration = configuration
        this.preferences = preferences
        val remembered = configuration.rememberStatus && preferences.isSubscribed
        publish(remembered)
        // A subscription bought anywhere in the app (or approved later) shows at once.
        scope.launch {
            billing.purchaseUpdates.collect { update ->
                if (!update.isOK) return@collect
                // A purchase doesn't say whether it's a subscription: with no product list, Google Play is asked.
                if (configuration.productIds.isEmpty()) {
                    if (update.isPurchased) refresh()
                    return@collect
                }
                val active = update.purchases.filter(::isActiveSubscription)
                if (active.isEmpty()) return@collect
                setSubscribed(true)
                acknowledge(active)
            }
        }
        startMonitoring()
    }

    /** Returning to the app (which also covers the launch) and every interval while it's open. */
    private fun startMonitoring() {
        scope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { refresh() }
                }
            })
        }
        val interval = configuration.refreshIntervalMillis ?: return
        scope.launch {
            while (true) {
                delay(interval)
                if (isAppActive()) refresh()
            }
        }
    }
    // endregion

    // region Updating
    /** Re-reads the subscriptions Google Play holds; keeps the last answer if it can't be asked. */
    suspend fun refresh() {
        val purchases = billing.subscriptions() ?: return
        val active = purchases.filter(::isActiveSubscription)
        setSubscribed(active.isNotEmpty())
        hasUnacknowledgedSubscription = active.any { !it.isAcknowledged }
        acknowledge(active)
    }

    /** What Google Play (or a finished paywall purchase) reports. */
    fun setSubscribed(isSubscribed: Boolean) {
        if (configuration.rememberStatus) preferences?.isSubscribed = isSubscribed
        publish(isSubscribed)
    }

    /** What the app's server says about access granted outside Google Play. */
    fun setExternalEntitlement(isEntitled: Boolean) {
        _hasExternalEntitlement.value = isEntitled
        _isEntitled.value = _isSubscribed.value || isEntitled
    }

    private fun publish(isSubscribed: Boolean) {
        _isSubscribed.value = isSubscribed || configuration.forceSubscribed
        _isEntitled.value = _isSubscribed.value || _hasExternalEntitlement.value
    }

    private suspend fun acknowledge(purchases: List<Purchase>) {
        if (!configuration.acknowledge) return
        purchases.filterNot { it.isAcknowledged }.forEach { billing.acknowledge(it.purchaseToken) }
    }

    private fun isActiveSubscription(purchase: Purchase): Boolean =
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            (configuration.productIds.isEmpty() || purchase.products.any { it in configuration.productIds })

    private fun isAppActive(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    // endregion
}
