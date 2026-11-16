package com.aurawave.player.data

import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.PlaylistRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A collection a queue was started from: one of the listener's playlists, an album, a
 * YouTube Music playlist, or their liked songs. The newest one is kept, so Home can offer
 * to carry on with it.
 */
@Serializable
data class PlaySource(
    val title: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    /** One of the listener's own playlists, by its id in the library. */
    val playlistId: Long? = null,
    val album: AlbumRef? = null,
    val online: PlaylistRef? = null,
    val liked: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): PlaySource? =
            if (text.isBlank()) null else runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun playlist(id: Long, name: String, songs: Int, coverUrl: String?) =
            PlaySource(name, "Playlist · ${count(songs)}", coverUrl.orEmpty(), playlistId = id)

        fun album(ref: AlbumRef, coverUrl: String?) =
            PlaySource(ref.title, listOf(ref.type, ref.artist).filter { it.isNotBlank() }.joinToString(" · "), coverUrl ?: ref.coverUrl, album = ref)

        fun online(ref: PlaylistRef, coverUrl: String?) =
            PlaySource(ref.title, listOf("Playlist", ref.author).filter { it.isNotBlank() }.joinToString(" · "), coverUrl ?: ref.coverUrl, online = ref)

        fun liked(songs: Int, coverUrl: String?) =
            PlaySource("Liked songs", count(songs), coverUrl.orEmpty(), liked = true)

        private fun count(songs: Int) = if (songs == 1) "1 song" else "$songs songs"
    }
}
