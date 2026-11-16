package com.aurawave.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Measures how fast YouTube serves a whole song: one open-ended request versus ranged chunks. */
class LiveThroughputTest {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Test
    fun wholeSongDownloadSpeed() = runBlocking {
        val seed = InnerTube(http).searchSongs("tum hi aana jubin nautiyal").first()
        val src = StreamResolver(http).resolve(seed.videoId)
        println("song: ${seed.title}, ${src.contentLength} bytes, ${src.bitrateKbps}kbps")

        var t0 = System.currentTimeMillis()
        var total = 0L
        http.newCall(Request.Builder().url(src.url).header("Range", "bytes=0-").build()).execute().use { r ->
            val s = r.body.byteStream()
            val buf = ByteArray(32 * 1024)
            var mark = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total - mark >= 1_000_000) { mark = total; println("  open-ended: ${total / 1000} KB at ${System.currentTimeMillis() - t0} ms") }
            }
        }
        val openMs = System.currentTimeMillis() - t0
        println("open-ended: $total bytes in $openMs ms")

        // No Range header at all: what a player sends when it starts a song from the beginning.
        t0 = System.currentTimeMillis()
        var plain = 0L
        http.newCall(Request.Builder().url(src.url).build()).execute().use { r ->
            val s = r.body.byteStream()
            val buf = ByteArray(32 * 1024)
            while (plain < 1_500_000 && System.currentTimeMillis() - t0 < 20_000) {
                val n = s.read(buf)
                if (n < 0) break
                plain += n
            }
        }
        println("no range header: $plain bytes in ${System.currentTimeMillis() - t0} ms")

        t0 = System.currentTimeMillis()
        var pos = 0L
        val chunk = 512 * 1024L
        while (pos < src.contentLength) {
            val end = minOf(pos + chunk, src.contentLength) - 1
            http.newCall(Request.Builder().url(src.url).header("Range", "bytes=$pos-$end").build()).execute().use { r ->
                pos += r.body.bytes().size
            }
        }
        val chunkMs = System.currentTimeMillis() - t0
        println("512 KB chunks: $pos bytes in $chunkMs ms")
        assertTrue(total == src.contentLength && pos == src.contentLength)
    }
}
