package com.tangent.sdk.internal

import android.content.Context
import android.content.SharedPreferences

/** The little the SDK remembers between launches, in its own preferences file (apart from the app's). */
internal class SdkPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether the app was opened before (decides `app_installed` / `first_app_open` vs `app_open`). */
    var hasOpenedAppBefore: Boolean
        get() = preferences.getBoolean(HAS_OPENED_APP_BEFORE, false)
        set(value) = preferences.edit().putBoolean(HAS_OPENED_APP_BEFORE, value).apply()

    /** The app version of the last launch (decides `app_updated`). */
    var previousAppVersion: String?
        get() = preferences.getString(PREVIOUS_APP_VERSION, null)
        set(value) = preferences.edit().putString(PREVIOUS_APP_VERSION, value).apply()

    /** The last subscription answer, so the app opens on the right screen before Google Play is asked again. */
    var isSubscribed: Boolean
        get() = preferences.getBoolean(IS_SUBSCRIBED, false)
        set(value) = preferences.edit().putBoolean(IS_SUBSCRIBED, value).apply()

    /** Whether anything about launches was recorded yet (an install that predates the SDK has nothing). */
    val hasLaunchState: Boolean
        get() = preferences.contains(HAS_OPENED_APP_BEFORE) || preferences.contains(PREVIOUS_APP_VERSION)

    private companion object {
        const val FILE_NAME = "com.tangent.sdk"
        const val HAS_OPENED_APP_BEFORE = "has_opened_app_before"
        const val PREVIOUS_APP_VERSION = "previous_app_version"
        const val IS_SUBSCRIBED = "is_subscribed"
    }
}
