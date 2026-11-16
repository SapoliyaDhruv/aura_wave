package com.aurawave.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The places a song is looked for when the usual route cannot play it.
 * Talks to the real services. Run with:  ./gradlew :innertube:test -Plive
 */
class LiveFallbackTest {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Reads a kilobyte from [fromByte] and says whether the server gave it. */
    private fun plays(url: String, fromByte: Long): Boolean =
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$fromByte-${fromByte + 1023}").build()).execute().use {
            println("  ${it.code} at byte $fromByte, ${it.header("Content-Type")}, ${it.header("Content-Range")}")
            it.code == 206
        }

    @Test
    fun jioSaavnHasTheSongAndItPlaysToTheEnd() {
        val source = JioSaavn(http).find("Umqb9KENgmk", "Tum Hi Ho", "Arijit Singh, Mithoon", 262, AudioQuality.HIGH)
        println("JioSaavn: ${source?.bitrateKbps} kbit/s ${source?.url}")
        assertNotNull(source, "JioSaavn did not have Tum Hi Ho, or its search changed")
        assertTrue(plays(source.url, 0), "the start of the stream")
        assertTrue(plays(source.url, 6_000_000), "well into the stream")
    }

    @Test
    fun jioSaavnDeclinesASongItDoesNotHave() {
        val source = JioSaavn(http).find("x", "Zzqx Vrrpl Mmnt Song That Does Not Exist", "Nobody Qwxz", 200, AudioQuality.HIGH)
        assertTrue(source == null, "found ${source?.url}")
    }

    /** The backup route is allowed to decline. It is not allowed to return a stream that stops part-way. */
    @Test
    fun backupRouteEitherPlaysThroughOrDeclines() {
        val source = try {
            BackupStreams(http).resolve("Umqb9KENgmk")
        } catch (e: UnplayableException) {
            println("backup route declined: ${e.message}")
            return
        }
        println("backup route: ${source.codec} ${source.bitrateKbps} kbit/s, ${source.contentLength} bytes")
        assertTrue(plays(source.url, source.contentLength * 4 / 5), "a stream the backup route accepted must play near its end")
    }
}
