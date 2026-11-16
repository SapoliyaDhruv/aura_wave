package com.aurawave.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCookiesTest {
    @Test
    fun sapisidHashMatchesTheWebsitesFormula() {
        val cookies = AccountCookies("PREF=f6=40000000; SAPISID=abc123/XYZ; HSID=q")
        assertTrue(cookies.isUsable)
        // sha1("1700000000 abc123/XYZ https://music.youtube.com")
        val expected = java.security.MessageDigest.getInstance("SHA-1")
            .digest("1700000000 abc123/XYZ https://music.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", cookies.authorization("https://music.youtube.com", 1_700_000_000))
    }

    @Test
    fun withoutSapisidTheLoginIsNotUsable() {
        assertFalse(AccountCookies("PREF=x; YSC=y").isUsable)
    }
}
