package com.aurawave.player.data

import com.aurawave.innertube.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The songs played most recently, newest first, each song once.
 * A small file is enough for this; the full listening history and statistics
 * will live in a database.
 */
class RecentStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val state = MutableStateFlow(load())
    val tracks: StateFlow<List<Track>> = state

    fun record(track: Track) {
        val current = state.value
        if (current.firstOrNull()?.videoId == track.videoId) return
        val next = (listOf(track) + current.filter { it.videoId != track.videoId }).take(MAX)
        state.value = next
        writer.launch { save(next) }
    }

    private fun load(): List<Track> =
        runCatching { json.decodeFromString<List<Track>>(file.readText()) }.getOrDefault(emptyList())

    private fun save(list: List<Track>) {
        runCatching {
            // Write beside the file, then swap, so a crash never leaves half a file.
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX = 200
    }
}
