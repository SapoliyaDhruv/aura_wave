package com.aurawave.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real YouTube. Run with:  ./gradlew :innertube:test -Plive
 * This is the check to run first whenever songs stop playing.
 */
class LiveYouTubeTest {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val tube = InnerTube(http)

    @Test
    fun searchFindsTheSong() = runBlocking {
        val res = tube.search("tum hi ho arijit singh")
        println("search: ${res.songs.size} songs, ${res.videos.size} videos, ${res.artists.size} artists")
        res.songs.take(3).forEach { println("  ${it.kind} ${it.videoId} ${it.title} / ${it.artist} / ${it.album} / ${it.durationSec}s") }
        val top = res.songs.first()
        assertTrue(top.title.contains("Tum Hi Ho", ignoreCase = true), "top result was ${top.title}")
        assertTrue(top.durationSec > 120)
        assertTrue(top.coverUrl.startsWith("https://"))
        assertTrue(res.artists.isNotEmpty())
    }

    @Test
    fun suggestionsWhileTyping() = runBlocking {
        val list = tube.suggestions("arij")
        println("suggestions: $list")
        assertTrue(list.isNotEmpty())
    }

    @Test
    fun radioAndLyricsAndArtist() = runBlocking {
        val seed = tube.searchSongs("kesariya arijit singh").first()
        val radio = tube.radio(seed.videoId)
        println("radio from ${seed.title}: ${radio.size} tracks, e.g. ${radio.take(3).map { it.title }}")
        assertTrue(radio.size >= 10)
        assertTrue(radio.none { it.videoId == seed.videoId })

        val lyrics = tube.lyrics(seed.videoId)
        println("lyrics: ${lyrics?.plain?.take(60)?.replace('\n', ' ')} (${lyrics?.source})")

        val page = tube.artist(name = "Arijit Singh")
        assertNotNull(page)
        println("artist: ${page.name}, ${page.audience}, ${page.songs.size} songs")
        assertTrue(page.songs.size >= 5)
    }

    @Test
    fun resolveFindsExactRecordingAndRejectsNonsense() = runBlocking {
        val hit = tube.resolve("Channa Mereya", "Pritam, Arijit Singh", 289)
        println("resolve: ${hit.track?.title} / ${hit.track?.artist} via ${hit.via} (${hit.confidence})")
        assertNotNull(hit.track)
        val miss = tube.resolve("Zzqx Vvkpl Wrtyy", "Nobody Qwxz", 200)
        println("resolve nonsense: ${miss.track} (${miss.bestRejected})")
        assertEquals(null, miss.track)
    }

    @Test
    fun syncedLyricsFromLrclib() = runBlocking {
        val song = tube.searchSongs("kesariya arijit singh brahmastra").first()
        val lyrics = Lrclib(http).find(song)
        println("lrclib for ${song.title}: synced=${lyrics?.isSynced}, ${lyrics?.lines?.size} lines, first: ${lyrics?.lines?.firstOrNull()}")
        assertNotNull(lyrics)
        assertTrue(lyrics.isSynced && lyrics.lines.size > 10)
        assertTrue(lyrics.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }

    @Test
    fun audioStreamIsPlayable() = runBlocking {
        val seed = tube.searchSongs("tum hi ho arijit singh").first()
        val resolver = StreamResolver(http)
        val started = System.currentTimeMillis()
        val src = resolver.resolve(seed.videoId)
        println("stream: ${src.codec} ${src.bitrateKbps}kbps ${src.mimeType} ${src.contentLength} bytes, resolved in ${System.currentTimeMillis() - started}ms, expires in ${(src.expiresAtMs - System.currentTimeMillis()) / 60000} min")
        assertTrue(src.url.startsWith("https://"))
        assertTrue(src.isFresh())

        // The first megabyte, the way a player would ask for it.
        val t0 = System.currentTimeMillis()
        val ranged = http.newCall(Request.Builder().url(src.url).header("Range", "bytes=0-1048575").build()).execute()
        val bytes = ranged.use { it.body.bytes().size }
        val ms = System.currentTimeMillis() - t0
        println("ranged GET: HTTP ${ranged.code}, $bytes bytes in ${ms}ms (${bytes / 1024 * 1000 / maxOf(1, ms)} KB/s)")
        assertTrue(ranged.code == 206 || ranged.code == 200, "HTTP ${ranged.code}")
        assertTrue(bytes > 100_000)

        // Seeking into the middle, with an open-ended range (what ExoPlayer sends after a seek).
        val t1 = System.currentTimeMillis()
        val mid = http.newCall(Request.Builder().url(src.url).header("Range", "bytes=1048576-").build()).execute()
        var read = 0
        mid.use { r ->
            val stream = r.body.byteStream()
            val buf = ByteArray(64 * 1024)
            while (read < 1_500_000) {
                val n = stream.read(buf)
                if (n < 0) break
                read += n
            }
        }
        val ms1 = System.currentTimeMillis() - t1
        println("open-ended GET: HTTP ${mid.code}, $read bytes in ${ms1}ms (${read / 1024 * 1000 / maxOf(1, ms1)} KB/s)")
        assertTrue(mid.code == 206, "HTTP ${mid.code}")

        val low = resolver.resolve(seed.videoId, AudioQuality.LOW)
        println("low quality: ${low.codec} ${low.bitrateKbps}kbps")
        assertTrue(low.bitrateKbps <= src.bitrateKbps)
    }
}
