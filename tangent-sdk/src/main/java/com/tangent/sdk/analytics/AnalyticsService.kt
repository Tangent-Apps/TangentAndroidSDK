package com.tangent.sdk.analytics

import android.content.Context
import com.mixpanel.android.mpmetrics.MixpanelAPI
import com.tangent.sdk.adjust.AdjustManager
import com.tangent.sdk.internal.SdkPreferences
import com.tangent.sdk.internal.TangentLog
import com.tangent.sdk.internal.toJSONObject
import org.json.JSONObject

/**
 * Analytics (iOS's `AnalyticsService` + `MixpanelManager`): every event goes to Mixpanel with the platform and app
 * version, and to Adjust when the app mapped its name to an Adjust token. Off (calls are no-ops) without a Mixpanel
 * token; Adjust forwarding still works.
 */
class AnalyticsService internal constructor(
    private val adjust: AdjustManager
) {
    /** What an install that predates the SDK already knew, so it isn't counted as a new install once on the SDK. */
    data class LegacyLaunchState(val hasOpenedAppBefore: Boolean, val previousAppVersion: String?)

    // region Properties
    /** The Mixpanel instance, for anything this class doesn't cover (`null` without a token). */
    var mixpanel: MixpanelAPI? = null
        private set
    private var appVersion: String = ""
    private var preferences: SdkPreferences? = null
    // endregion

    // region Setup
    internal fun start(context: Context, token: String?, appVersion: String, isDebug: Boolean, preferences: SdkPreferences) {
        this.appVersion = appVersion
        this.preferences = preferences
        if (token == null) return
        mixpanel = MixpanelAPI.getInstance(context, token, true).apply {
            setEnableLogging(isDebug)
        }
        TangentLog.i("✅ Mixpanel: started")
    }
    // endregion

    // region Events
    fun track(event: AnalyticsEvent) {
        track(event.name, event.properties)
    }

    /**
     * Every event carries `platform` ("Android") and `app_version`, like iOS — except an event's own `platform`
     * (e.g. a recipe import's "tiktok"), which is kept; Mixpanel's `$os` still says Android.
     */
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val json = properties.toJSONObject().put(APP_VERSION, appVersion)
        if (!json.has(PLATFORM)) json.put(PLATFORM, PLATFORM_ANDROID)
        mixpanel?.track(name, json)
        adjust.trackEvent(name, properties.stringValues())
        TangentLog.d("📊 Tracked '$name' with properties: $json")
    }

    /** Starts timing [name]: its next [track] carries a `$duration`. */
    fun timeEvent(name: String) {
        mixpanel?.timeEvent(name)
    }

    /** The onboarding step event every app sends: `onboarding_[1.0]_3_goal_completed` for the 3rd screen. */
    fun trackOnboardingStep(index: Int, screenName: String, properties: Map<String, Any?> = emptyMap()) {
        track("onboarding_[$appVersion]_${index + 1}_${screenName}_completed", properties)
    }
    // endregion

    // region Launches
    /** `app_installed` + `first_app_open` on the first launch, `app_open` on later ones, and `app_updated` on the
     *  first launch of a new version. Sent by `TangentSDK.initialize` unless `trackAppLaunches` is off. */
    internal fun trackLaunch(legacy: LegacyLaunchState?) {
        val preferences = preferences ?: return
        if (legacy != null && !preferences.hasLaunchState) {
            preferences.hasOpenedAppBefore = legacy.hasOpenedAppBefore
            legacy.previousAppVersion?.let { preferences.previousAppVersion = it }
        }
        if (preferences.hasOpenedAppBefore) {
            track(StandardEvents.APP_OPEN)
        } else {
            track(StandardEvents.APP_INSTALLED)
            track(StandardEvents.FIRST_APP_OPEN)
            preferences.hasOpenedAppBefore = true
        }
        val previousVersion = preferences.previousAppVersion
        if (previousVersion != null && previousVersion != appVersion) {
            track(StandardEvents.APP_UPDATED, mapOf("from_version" to previousVersion, "to_version" to appVersion))
        }
        preferences.previousAppVersion = appVersion
    }
    // endregion

    // region Users
    /** Ties this device's events to [userId] (Mixpanel's distinct ID). */
    fun identify(userId: String) {
        mixpanel?.identify(userId)
    }

    fun setUserProfile(properties: Map<String, Any?>) {
        mixpanel?.people?.set(properties.toJSONObject())
    }

    fun setUserProperty(key: String, value: Any?) {
        mixpanel?.people?.set(key, JSONObject.wrap(value))
    }

    /** Sent with every event from now on, across launches (e.g. `is_plus`, so every event splits by free / paid). */
    fun registerSuperProperties(properties: Map<String, Any?>) {
        mixpanel?.registerSuperProperties(properties.toJSONObject())
    }

    fun unregisterSuperProperty(name: String) {
        mixpanel?.unregisterSuperProperty(name)
    }

    fun clearSuperProperties() {
        mixpanel?.clearSuperProperties()
    }

    /** A new anonymous user (e.g. after deleting the account). */
    fun reset() {
        mixpanel?.reset()
    }

    fun flush() {
        mixpanel?.flush()
    }
    // endregion

    // region Revenue
    /** Revenue on the user's Mixpanel profile (People → Revenue). */
    fun trackRevenue(amount: Double, productId: String, currency: String = "USD", transactionId: String? = null) {
        val properties = mapOf(
            "amount" to amount,
            "product_id" to productId,
            "currency" to currency,
            "transaction_id" to transactionId
        )
        mixpanel?.people?.trackCharge(amount, properties.toJSONObject())
    }
    // endregion

    private fun Map<String, Any?>.stringValues(): Map<String, String> =
        mapNotNull { (key, value) -> value?.let { key to it.toString() } }.toMap()

    private companion object {
        const val PLATFORM = "platform"
        const val PLATFORM_ANDROID = "Android"
        const val APP_VERSION = "app_version"
    }
}
