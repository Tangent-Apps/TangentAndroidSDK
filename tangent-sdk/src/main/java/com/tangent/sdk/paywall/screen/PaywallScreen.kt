package com.tangent.sdk.paywall.screen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import com.superwall.sdk.paywall.view.webview.SWWebView
import com.tangent.sdk.internal.ActivityCallbacks

/**
 * Fixes for Superwall's Android paywall screen, put on it each time it opens, so the paywalls behave like on iOS.
 */
object PaywallScreen {
    /**
     * @param unlockScrolling let drags scroll inside paywalls that have page scrolling off (Superwall's web view drops
     *   every finger move then, so content below the fold is unreachable on short phones). See [PaywallScrollUnlock].
     * @param lightSystemBarIcons keep the status / navigation bar icons light, for dark paywalls (AndroidX makes them
     *   dark whenever the phone is in light mode).
     * @param fitSheetsToContent open sheet (drawer) paywalls at their content's height and keep them there, instead of
     *   Superwall's fixed 70%. See [PaywallSheetSizing].
     * @param statusBarCssVariable a CSS variable set on the paywall page to the status bar height it sits under, in CSS
     *   px (e.g. `--status-bar`; Superwall's web view reports no safe area). Use it in the editor as
     *   `var(--status-bar, 44px)`. `null` = not set. See [PaywallStatusBarInset].
     */
    data class Options(
        val unlockScrolling: Boolean = true,
        val lightSystemBarIcons: Boolean = false,
        val fitSheetsToContent: Boolean = false,
        val statusBarCssVariable: String? = null
    )

    internal const val PAYWALL_ACTIVITY = "com.superwall.sdk.paywall.view.SuperwallPaywallActivity"

    internal fun install(application: Application, options: Options) {
        if (options == Options(unlockScrolling = false)) return
        application.registerActivityLifecycleCallbacks(object : ActivityCallbacks() {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name != PAYWALL_ACTIVITY) return
                if (options.lightSystemBarIcons) {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                if (options.unlockScrolling) PaywallScrollUnlock.install(activity)
                if (options.fitSheetsToContent) PaywallSheetSizing.install(activity)
                options.statusBarCssVariable?.let { PaywallStatusBarInset.install(activity, it) }
            }
        })
    }

    internal fun findWebView(view: View): SWWebView? {
        if (view is SWWebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
