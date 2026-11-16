package com.aurawave.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Song/Video switch pairs a song with its official music video. This asks the real
 * YouTube Music for a handful of songs and prints each pairing, so a wrong one is easy to spot.
 * Run with:  ./gradlew :innertube:test -Plive --tests '*LiveVideoTwinTest*' -i
 */
class LiveVideoTwinTest {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val tube = InnerTube(http)

    @Test
    fun songsArePairedWithTheirOwnVideo() = runBlocking {
        val queries = listOf(
            "kesariya arijit singh", "tum hi ho arijit singh", "apna bana le bhediya", "chaleya jawan",
            "raataan lambiyan", "kala chashma", "tere vaaste", "heeriye arijit singh", "pasoori", "blinding lights the weeknd",
        )
        var paired = 0
        for (q in queries) {
            val song = tube.searchSongs(q).firstOrNull { it.kind == Kind.SONG } ?: continue
            val video = tube.musicVideoFor(song)
            println("SONG  ${song.videoId} ${song.durationSec}s  ${song.title} / ${song.artist}")
            println("  ->  ${video?.videoId} ${video?.durationSec}s  ${video?.title} / ${video?.artist}")
            if (video != null) {
                paired++
                assertTrue(video.kind == Kind.VIDEO)
                assertTrue(video.videoId != song.videoId)
                assertTrue(Match.titleSimilarity(song.title, video.title) >= 0.6, "${song.title} was paired with ${video.title}")
            }
        }
        assertTrue(paired >= queries.size / 2, "only $paired of ${queries.size} songs were paired with a video")
    }
}
