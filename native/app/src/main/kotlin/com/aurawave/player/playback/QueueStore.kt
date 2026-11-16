package com.aurawave.player.playback

import com.aurawave.innertube.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The queue as it was when the app was last used, so it reopens where the listener left off. */
@Serializable
data class SavedQueue(
    val tracks: List<Track>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/**
 * Keeps the queue in two files: the songs, written when the queue changes, and the
 * place in it, written often. A long queue is then not rewritten every few seconds.
 */
class QueueStore(dir: File) {
    private val songsFile = File(dir, "queue.json")
    private val placeFile = File(dir, "queue_place.txt")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SavedQueue? = runCatching {
        if (!songsFile.exists()) return null
        val saved = json.decodeFromString(SavedQueue.serializer(), songsFile.readText())
        val place = placeFile.takeIf { it.exists() }?.readText()?.trim()?.split(',')
        val index = place?.getOrNull(0)?.toIntOrNull() ?: saved.index
        val position = place?.getOrNull(1)?.toLongOrNull() ?: saved.positionMs
        saved.copy(index = index.coerceIn(0, maxOf(0, saved.tracks.lastIndex)), positionMs = position.coerceAtLeast(0))
    }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }

    fun saveSongs(queue: SavedQueue) = writeSafely(songsFile, json.encodeToString(SavedQueue.serializer(), queue))

    fun savePlace(index: Int, positionMs: Long) = writeSafely(placeFile, "$index,$positionMs")

    fun clear() {
        songsFile.delete()
        placeFile.delete()
    }

    /** Writes to a temporary file first, so a crash mid-write never leaves a broken queue behind. */
    private fun writeSafely(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }
}
