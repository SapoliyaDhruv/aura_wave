package com.aurawave.player.data

import com.aurawave.innertube.ArtistPage
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.SearchResult
import com.aurawave.innertube.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * Every way the app finds music goes through here. Answers are remembered for a
 * while so going back to a screen is instant and costs no data.
 */
class Catalog(private val tube: InnerTube) {
    private class Entry(val value: Any?, val at: Long)

    private val memory = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> remembered(key: String, maxAgeMs: Long, load: suspend () -> T): T {
        memory[key]?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }?.let { return it.value as T }
        val value = load()
        if (memory.size > MAX_ENTRIES) memory.clear()
        memory[key] = Entry(value, System.currentTimeMillis())
        return value
    }

    suspend fun search(query: String): SearchResult {
        val q = query.trim()
        return remembered("search:${q.lowercase()}", 30 * MINUTE) { tube.search(q) }
    }

    suspend fun suggestions(input: String): List<String> {
        val q = input.trim()
        return remembered("suggest:${q.lowercase()}", 30 * MINUTE) { tube.suggestions(q) }
    }

    suspend fun radio(videoId: String): List<Track> =
        remembered("radio:$videoId", 60 * MINUTE) { tube.radio(videoId) }

    suspend fun artist(id: String, name: String): ArtistPage? =
        remembered("artist:${id.ifEmpty { name.lowercase() }}", 60 * MINUTE) { tube.artist(id, name) }

    private companion object {
        const val MINUTE = 60_000L
        const val MAX_ENTRIES = 200
    }
}
