package com.tangent.sdk.internal

import org.json.JSONObject

/** A property map as Mixpanel wants it. Nulls are left out; lists, maps and arrays become JSON ones. */
internal fun Map<String, Any?>.toJSONObject(): JSONObject {
    val json = JSONObject()
    for ((key, value) in this) {
        if (value == null) continue
        json.put(key, JSONObject.wrap(value))
    }
    return json
}
