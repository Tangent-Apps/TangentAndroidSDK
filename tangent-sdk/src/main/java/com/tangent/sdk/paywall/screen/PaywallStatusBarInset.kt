package com.tangent.sdk.paywall.screen

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.superwall.sdk.paywall.view.webview.SWWebView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tells Superwall's paywall page how much of it sits under the status bar (and camera cutout). Superwall draws its
 * full-screen paywalls behind the status bar but its web view reports no safe area, so a top spacer in the editor can
 * use `var(<cssVariable>, 44px)`: this sets that variable, in CSS px, to the phone's real status bar height (24 to
 * ~52dp, so a fixed spacer either wastes space or leaves the close button under the clock). Sheet paywalls sit below
 * the status bar and get 0.
 */
internal object PaywallStatusBarInset {
    fun install(activity: Activity, cssVariable: String) {
        // The paywall page can arrive after the screen opens, so the variable is set again after every layout.
        activity.window.decorView.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            PaywallScreen.findWebView(root)?.let { apply(it, cssVariable) }
        }
    }

    private fun apply(webView: SWWebView, cssVariable: String) {
        val insets = ViewCompat.getRootWindowInsets(webView) ?: return
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
        val location = IntArray(2).also(webView::getLocationInWindow)
        val coveredPx = max(0, top - location[1])
        val cssPx = (coveredPx / webView.resources.displayMetrics.density).roundToInt()
        webView.evaluateJavascript(
            "document.documentElement.style.setProperty('$cssVariable','${cssPx}px')",
            null
        )
    }
}
