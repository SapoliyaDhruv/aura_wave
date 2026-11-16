package com.aurawave.innertube

import java.security.MessageDigest

/** Who is signed in. */
data class AccountInfo(val name: String, val handle: String, val photoUrl: String)

/**
 * The cookies of a YouTube Music web login. Requests about the account carry them, plus
 * the "SAPISIDHASH" header the website computes from the SAPISID cookie.
 */
class AccountCookies(val cookie: String) {
    private val sapisid: String? = parse(cookie)["SAPISID"] ?: parse(cookie)["__Secure-3PAPISID"]

    val isUsable: Boolean get() = sapisid != null

    fun authorization(origin: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val sid = sapisid ?: return ""
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSec $sid $origin".toByteArray())
        return "SAPISIDHASH ${nowSec}_${digest.joinToString("") { "%02x".format(it) }}"
    }

    companion object {
        fun parse(cookie: String): Map<String, String> = cookie.split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
    }
}
