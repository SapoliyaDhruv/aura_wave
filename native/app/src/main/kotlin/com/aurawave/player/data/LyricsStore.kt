package com.aurawave.player.data

import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.Lrclib
import com.aurawave.innertube.SongLyrics
import com.aurawave.innertube.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics for a song: synced lines from LRCLIB when they exist, otherwise plain
 * text from LRCLIB or YouTube Music. Answers are remembered while the app runs.
 */
class LyricsStore(private val lrclib: Lrclib, private val tube: InnerTube) {
    private class Entry(val lyrics: SongLyrics?, val at: Long)

    private val memory = ConcurrentHashMap<String, Entry>()

    suspend fun get(track: Track): SongLyrics? {
        memory[track.videoId]?.let { e ->
            // "Nothing found" may just have been a bad connection, so it is only trusted briefly.
            if (e.lyrics != null || System.currentTimeMillis() - e.at < MISS_MEMORY_MS) return e.lyrics
        }
        val fromLrclib = lrclib.find(track)
        val lyrics = when {
            fromLrclib?.isSynced == true -> fromLrclib
            !fromLrclib?.plain.isNullOrBlank() -> fromLrclib
            else -> runCatching { tube.lyrics(track.videoId) }.getOrNull()
                ?.let { SongLyrics(plain = it.plain, source = it.source.ifEmpty { "YouTube Music" }) }
        }
        memory[track.videoId] = Entry(lyrics, System.currentTimeMillis())
        return lyrics
    }

    private companion object {
        const val MISS_MEMORY_MS = 5 * 60_000L
    }
}
