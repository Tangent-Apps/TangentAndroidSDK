package com.tangent.sdk.paywall.screen

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sizes Superwall's sheet paywalls to their content, like iOS sheets, and keeps them at that height. Superwall opens
 * its drawer at a fixed 70% of the screen (cutting the buy button off on short phones) and leaves the full-screen
 * state reachable by dragging up. Here the paywall page is asked how tall its content is, and the drawer opens at
 * exactly that height (never taller than the screen); dragging up does nothing, dragging down and the close button
 * still dismiss it. Until the page answers (or if it can't), the drawer uses 70% of the screen, at least
 * [FALLBACK_MIN_HEIGHT_DP]. Full-screen paywalls have no drawer and aren't touched.
 *
 * The sheet paywall's root stack (Superwall editor) needs no minimum height, so its height is its content's.
 */
internal object PaywallSheetSizing {
    private const val FALLBACK_SCREEN_SHARE = 0.7f
    private const val FALLBACK_MIN_HEIGHT_DP = 620

    /** The paywall's top-level container is the first stack in the page: its height is the content's, in CSS px. */
    private const val CONTENT_HEIGHT_SCRIPT =
        "(function(){var r=document.querySelector('.sw-stack');return r?r.getBoundingClientRect().height:0;})()"

    /** Content heights in px per drawer, once the page has answered (`null` while it's being asked). */
    private val contentHeights = WeakHashMap<BottomSheetBehavior<*>, Float?>()

    fun install(activity: Activity) {
        // Superwall sets the drawer up once its views are laid out, so this looks after every layout.
        activity.window.decorView.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            sizeDrawers(root)
        }
    }

    private fun sizeDrawers(view: View) {
        val behavior = (view.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? BottomSheetBehavior<*>
        if (behavior != null) {
            size(view, behavior)
            return
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) sizeDrawers(view.getChildAt(index))
    }

    /** Opens at the content's height, and fully expanded = that same position. */
    private fun size(sheet: View, behavior: BottomSheetBehavior<*>) {
        val parentHeight = (sheet.parent as? View)?.height ?: return
        if (parentHeight == 0) return
        if (!contentHeights.containsKey(behavior)) measureContent(sheet, behavior)
        val density = sheet.resources.displayMetrics.density
        val wantedHeight = contentHeights[behavior]
            ?: max(parentHeight * FALLBACK_SCREEN_SHARE, FALLBACK_MIN_HEIGHT_DP * density)
        val visibleHeight = min(wantedHeight, parentHeight.toFloat())
        val top = (parentHeight - visibleHeight).roundToInt()
        if (!behavior.isFitToContents && behavior.expandedOffset == top) return
        behavior.isFitToContents = false
        // The half-expanded ratio has to stay below 1; at the full screen height the expanded position is the one used.
        behavior.halfExpandedRatio = (visibleHeight / parentHeight).coerceIn(0.01f, 0.99f)
        behavior.expandedOffset = top
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /** Asks the paywall page for its content height once, then sizes the drawer again with it. */
    private fun measureContent(sheet: View, behavior: BottomSheetBehavior<*>) {
        val webView = PaywallScreen.findWebView(sheet) ?: return
        contentHeights[behavior] = null
        webView.evaluateJavascript(CONTENT_HEIGHT_SCRIPT) { result ->
            val cssHeight = result?.toFloatOrNull() ?: 0f
            if (cssHeight <= 0f) {
                contentHeights.remove(behavior) // try again on the next layout
                return@evaluateJavascript
            }
            contentHeights[behavior] = cssHeight * sheet.resources.displayMetrics.density
            size(sheet, behavior)
        }
    }
}
