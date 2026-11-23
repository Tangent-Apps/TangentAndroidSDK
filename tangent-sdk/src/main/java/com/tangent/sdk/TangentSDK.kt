package com.tangent.sdk

import android.app.Application
import com.tangent.sdk.adjust.AdjustManager
import com.tangent.sdk.adjust.AdjustPaywallTracking
import com.tangent.sdk.analytics.AnalyticsService
import com.tangent.sdk.billing.PlayBillingManager
import com.tangent.sdk.billing.SubscriptionManager
import com.tangent.sdk.internal.SdkPreferences
import com.tangent.sdk.internal.TangentLog
import com.tangent.sdk.paywall.SuperwallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android Tangent SDK (iOS's `TangentSwiftSDK`): Mixpanel, Adjust, Google Play Billing and Superwall, set up the
 * same way in every Tangent app. Initialize once from `Application.onCreate` (after `super.onCreate()`), then use
 * [analytics], [adjust], [billing], [subscriptions] and [superwall].
 *
 * Every service is optional: one whose key is missing (`null` or blank) stays off and its calls do nothing.
 */
object TangentSDK {
    /**
     * @param isDebug the app's `BuildConfig.DEBUG`: verbose logs, and Adjust's sandbox unless [AdjustConfiguration.environment] says otherwise.
     * @param appVersion the app's `BuildConfig.VERSION_NAME`, sent with every event.
     * @param mixpanelToken Mixpanel project token.
     * @param adjust Adjust app token and events.
     * @param superwall Superwall key and paywall behaviour.
     * @param subscriptions which Google Play subscriptions grant access, and how they're checked.
     * @param userId suspends until the signed-in user's account ID is known (see `AccountId`), e.g.
     *   `{ AccountId.uuidV5(NAMESPACE, authService.awaitUid()) }`. Superwall is identified with it, and Google Play
     *   purchases are stamped with it. `null` = Superwall stays anonymous and purchases unstamped.
     * @param trackAppLaunches send `app_installed` / `first_app_open` / `app_open` / `app_updated` at initialize.
     * @param legacyLaunchState for an app moving onto the SDK: what it already knew about launches, so existing users
     *   aren't counted as new installs. Read only the first time.
     */
    data class Configuration(
        val isDebug: Boolean,
        val appVersion: String,
        val mixpanelToken: String? = null,
        val adjust: AdjustConfiguration? = null,
        val superwall: SuperwallManager.Configuration? = null,
        val subscriptions: SubscriptionManager.Configuration = SubscriptionManager.Configuration(),
        val userId: (suspend () -> String)? = null,
        val trackAppLaunches: Boolean = true,
        val legacyLaunchState: AnalyticsService.LegacyLaunchState? = null
    )

    /**
     * @param appToken Adjust's app token for Android.
     * @param environment `null` = sandbox in debug builds, production otherwise.
     * @param paywallTracking paywall views and first purchases with revenue (see [AdjustPaywallTracking]).
     * @param eventTokens analytics event name → Adjust event token: [analytics] forwards only these names to Adjust.
     */
    data class AdjustConfiguration(
        val appToken: String?,
        val environment: AdjustManager.Environment? = null,
        val paywallTracking: AdjustPaywallTracking? = null,
        val eventTokens: Map<String, String> = emptyMap()
    )

    // region Services
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Adjust attribution and events. */
    val adjust = AdjustManager()

    /** Mixpanel events (forwarded to Adjust when mapped), users and super properties. */
    val analytics = AnalyticsService(adjust)

    /** Google Play Billing: products, the purchase sheet, owned purchases. */
    val billing = PlayBillingManager()

    /** Whether the user is subscribed (from Google Play) or otherwise entitled. */
    val subscriptions = SubscriptionManager(scope, billing)

    /** Superwall paywalls. */
    val superwall = SuperwallManager(scope, billing, subscriptions, adjust)
    // endregion

    // region Properties
    @Volatile
    var isInitialized = false
        private set
    private var configuration: Configuration? = null
    // endregion

    // region Initializing
    /** Starts every configured service; later calls are ignored. Adjust starts first, so Superwall can hand its device
     *  IDs to its Adjust integration. */
    @Synchronized
    fun initialize(application: Application, configuration: Configuration) {
        if (isInitialized) return
        this.configuration = configuration
        TangentLog.isDebug = configuration.isDebug
        val preferences = SdkPreferences(application)

        configuration.adjust?.let { adjustConfiguration ->
            val appToken = adjustConfiguration.appToken?.takeIf { it.isNotBlank() } ?: return@let
            adjust.start(
                application = application,
                appToken = appToken,
                environment = adjustConfiguration.environment
                    ?: if (configuration.isDebug) AdjustManager.Environment.SANDBOX else AdjustManager.Environment.PRODUCTION,
                isDebug = configuration.isDebug,
                paywallTracking = adjustConfiguration.paywallTracking,
                eventTokens = adjustConfiguration.eventTokens
            )
        }

        analytics.start(
            context = application,
            token = configuration.mixpanelToken?.takeIf { it.isNotBlank() },
            appVersion = configuration.appVersion,
            isDebug = configuration.isDebug,
            preferences = preferences
        )

        billing.start(application)
        billing.onPurchased = adjust::purchased
        subscriptions.start(configuration.subscriptions, preferences)

        configuration.superwall?.takeIf { it.apiKey.isNotBlank() }?.let { superwallConfiguration ->
            superwall.configure(application, superwallConfiguration) { accountId() }
        }

        isInitialized = true
        TangentLog.i("✅ TangentSDK: initialized")

        if (configuration.trackAppLaunches) analytics.trackLaunch(configuration.legacyLaunchState)
        identify()
    }
    // endregion

    // region Identity
    /**
     * Identifies Superwall with the app's user ID once it's known (waiting for a sign-in still on its way). Done at
     * initialize; call again when the account changes (e.g. a new account after deleting one).
     */
    fun identify() {
        val userId = configuration?.userId ?: return
        if (!superwall.isConfigured) return
        scope.launch {
            val id = runCatching { userId() }.getOrNull() ?: return@launch
            superwall.identify(id)
        }
    }

    /**
     * The user's account ID for stamping a purchase, waiting up to [timeoutMillis] for a sign-in still on its way
     * (e.g. a first launch). `null` without a `userId` provider or a sign-in in time.
     */
    suspend fun accountId(timeoutMillis: Long = ACCOUNT_ID_TIMEOUT_MILLIS): String? {
        val userId = configuration?.userId ?: return null
        return withTimeoutOrNull(timeoutMillis) { runCatching { userId() }.getOrNull() }
    }
    // endregion

    private const val ACCOUNT_ID_TIMEOUT_MILLIS = 15_000L
}
