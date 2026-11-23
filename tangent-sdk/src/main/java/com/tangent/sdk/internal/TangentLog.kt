package com.tangent.sdk.internal

import android.util.Log

/** The SDK's logcat output, tagged `TangentSDK`. Debug lines only show in the app's debug builds. */
internal object TangentLog {
    private const val TAG = "TangentSDK"
    var isDebug = false

    fun d(message: String) {
        if (isDebug) Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
