package com.tangent.sdk.paywall

import android.os.SystemClock

/**
 * Counts taps and fires once [count] of them land within [windowMillis] — e.g. a hidden reviewers' entry on a paywall
 * (a Superwall custom action sent on every tap of a badge, see `SuperwallManager.customActions`). Main thread only.
 */
class TapSequence(
    private val count: Int = 5,
    private val windowMillis: Long = 3_000L,
    private val onComplete: () -> Unit
) {
    private var taps = 0
    private var firstTapAt = 0L

    fun tap() {
        val now = SystemClock.uptimeMillis()
        if (taps == 0 || now - firstTapAt > windowMillis) {
            taps = 0
            firstTapAt = now
        }
        taps += 1
        if (taps >= count) {
            taps = 0
            onComplete()
        }
    }
}
