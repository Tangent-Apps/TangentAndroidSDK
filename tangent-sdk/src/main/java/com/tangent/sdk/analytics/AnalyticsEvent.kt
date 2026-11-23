package com.tangent.sdk.analytics

/**
 * An event the app tracks. Each app keeps its own sealed list (e.g. `MixPanelEvents`) and implements this, so the
 * SDK sends it without knowing the app's events:
 *
 * ```
 * sealed interface MixPanelEvents : AnalyticsEvent {
 *     data class PaywallShown(override val properties: Map<String, Any?>) : MixPanelEvents {
 *         override val name get() = "paywall_shown"
 *     }
 * }
 * ```
 */
interface AnalyticsEvent {
    val name: String
    val properties: Map<String, Any?>
        get() = emptyMap()
}

/** Events the SDK sends itself, the same names on every Tangent Android app. */
object StandardEvents {
    /** First launch after install, with [FIRST_APP_OPEN]. */
    const val APP_INSTALLED = "app_installed"
    const val FIRST_APP_OPEN = "first_app_open"
    /** Every later launch. */
    const val APP_OPEN = "app_open"
    /** First launch of a new version: `from_version`, `to_version`. */
    const val APP_UPDATED = "app_updated"
}
