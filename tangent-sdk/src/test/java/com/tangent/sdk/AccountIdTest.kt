package com.tangent.sdk

import com.tangent.sdk.identity.AccountId
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountIdTest {
    /** RFC 9562 test vector: UUIDv5 of "www.example.com" in the DNS namespace. */
    @Test
    fun matchesTheRfcVector() {
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            AccountId.uuidV5("6ba7b810-9dad-11d1-80b4-00c04fd430c8", "www.example.com")
        )
    }

    @Test
    fun isStableForTheSameUid() {
        val namespace = "0f9f4c3e-7b1a-4c2d-9e8f-1a2b3c4d5e6f"
        assertEquals(AccountId.uuidV5(namespace, "uid-1"), AccountId.uuidV5(namespace, "uid-1"))
    }
}
