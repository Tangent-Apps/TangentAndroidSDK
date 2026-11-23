package com.tangent.sdk.identity

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable account IDs made from the Firebase uid: a name-based UUIDv5 (RFC 9562), so iOS, Android and the server all
 * compute the same one with no call, and it survives a reinstall while the uid does.
 *
 * It's what Superwall identifies the user by and what stamps Google Play purchases (`obfuscatedAccountId`; iOS's
 * `appAccountToken`), which Google repeats on every renewal / refund notification so the server can match them.
 * Each app keeps its own namespace (the server's `ACCOUNT_TOKEN_NAMESPACE`), shared with its iOS app.
 */
object AccountId {
    fun uuidV5(namespace: String, name: String): String = uuidV5(UUID.fromString(namespace), name)

    fun uuidV5(namespace: UUID, name: String): String {
        val namespaceBytes = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits)
            .array()
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest(namespaceBytes + name.toByteArray(Charsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte() // version 5
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // RFC variant
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
