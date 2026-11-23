package com.tangent.sdk.paywall.screen

import android.app.Activity
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.superwall.sdk.paywall.view.webview.SWWebView
import kotlin.math.abs

/**
 * Lets drags scroll inside Superwall's paywalls, like on iOS. When a paywall has page scrolling off, Superwall's
 * Android web view throws away every finger move, which also stops scrolling *inside* the page (iOS only stops the page
 * itself). This watches the screen's touches first and, for that gesture only, lets the web view take moves again and
 * keeps the views above it out:
 * - a sideways drag, on any paywall (carousels, cards);
 * - an up or down drag, on full-screen paywalls only. On sheets those drags keep moving the drawer.
 * Taps work as before.
 */
internal object PaywallScrollUnlock {
    fun install(activity: Activity) {
        val window = activity.window
        val original = window.callback ?: return
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        window.callback = object : Window.Callback by original {
            private var startX = 0f
            private var startY = 0f
            private var isDecided = false
            /** The web view this gesture turned scrolling on for, to turn it back off when the finger lifts. */
            private var unlockedWebView: SWWebView? = null

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isDecided = false
                    }

                    MotionEvent.ACTION_MOVE -> if (!isDecided) {
                        val dx = abs(event.rawX - startX)
                        val dy = abs(event.rawY - startY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isDecided = true
                            if (dx > dy || !hasDrawer(window.decorView)) {
                                unlockedWebView = unlockScroll(window.decorView)
                            }
                        }
                    }
                }
                val handled = original.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    unlockedWebView?.scrollEnabled = false
                    unlockedWebView = null
                }
                return handled
            }

            // Interface default methods aren't forwarded by `by original`: pass them on so the screen behaves the same.
            override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
                original.onProvideKeyboardShortcuts(data, menu, deviceId)

            override fun onPointerCaptureChanged(hasCapture: Boolean) = original.onPointerCaptureChanged(hasCapture)
        }
    }

    /** Lets this gesture's moves reach the paywall page; returns the web view when it had scrolling off. */
    private fun unlockScroll(root: View): SWWebView? {
        val webView = PaywallScreen.findWebView(root) ?: return null
        webView.parent?.requestDisallowInterceptTouchEvent(true)
        if (webView.scrollEnabled) return null
        webView.scrollEnabled = true
        return webView
    }

    /** Whether the paywall is a sheet (Superwall's drawer is a Material bottom sheet). */
    private fun hasDrawer(view: View): Boolean {
        if ((view.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior is BottomSheetBehavior<*>) return true
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (hasDrawer(view.getChildAt(index))) return true
        }
        return false
    }
}
