package com.tangent.sdk.adjust

/**
 * Opt-in reporting of the paywall funnel to Adjust from the device (iOS's `AdjustPaywallTracking`): paywall views and
 * first purchases. Purchases are reported by `PlayBillingManager`, the one place every purchase passes (local paywalls
 * and Superwall's), so none is counted twice.
 *
 * Superwall's server integration keeps reporting renewals, cancellations and refunds to the same events. One Adjust app
 * usually covers iOS and Android, so these are the same tokens as the iOS app's.
 *
 * @param paywallViewedToken a paywall opening (sent without revenue).
 * @param trialStartedToken a free trial starting (sent without revenue: 0 would land the user in a revenue range).
 * @param subscriptionStartedToken a paid subscription starting.
 * @param oneTimePurchaseToken a one-time product (consumable / non-consumable).
 * @param takeHomeShare called at purchase time; return the share of the price kept (e.g. a Remote Config value), or
 *   `null` for [DEFAULT_TAKE_HOME_SHARE]. A value that can't be a share (≤ 0, > 1, NaN) is ignored, so a typo like 85
 *   can't zero or inflate revenue.
 */
class AdjustPaywallTracking(
    val paywallViewedToken: String,
    val trialStartedToken: String,
    val subscriptionStartedToken: String,
    val oneTimePurchaseToken: String,
    private val takeHomeShare: () -> Double? = { null }
) {
    /** The share used for revenue: the app's value when it's a valid share, else [DEFAULT_TAKE_HOME_SHARE]. */
    fun share(): Double =
        takeHomeShare()?.takeIf(::isValidShare) ?: DEFAULT_TAKE_HOME_SHARE

    companion object {
        /** Google Play keeps 15% of subscriptions, and of a small developer's first $1M a year of one-time products. */
        const val DEFAULT_TAKE_HOME_SHARE = 0.85

        internal fun isValidShare(share: Double): Boolean = share.isFinite() && share > 0 && share <= 1
    }
}
