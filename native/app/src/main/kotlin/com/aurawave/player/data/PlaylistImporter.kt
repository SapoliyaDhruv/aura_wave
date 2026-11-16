package com.aurawave.player.data

import com.aurawave.innertube.Match
import com.aurawave.innertube.Spotify
import com.aurawave.innertube.SpotifyException
import com.aurawave.innertube.Track
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brings a Spotify or YouTube playlist into the library from its link. Importing the
 * same playlist again adds its new songs to the copy made the first time.
 */
class PlaylistImporter(
    private val library: Library,
    private val catalog: Catalog,
    private val spotify: Spotify,
    private val scope: CoroutineScope,
) {
    sealed interface Progress {
        data object Idle : Progress
        data class Working(val step: String, val done: Int = 0, val total: Int = 0) : Progress
        data class Done(val playlistId: Long, val name: String, val added: Int, val unmatched: List<String>) : Progress
        data class Failed(val message: String) : Progress
    }

    private val state = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = state.asStateFlow()

    fun dismiss() {
        if (state.value !is Progress.Working) state.value = Progress.Idle
    }

    /** Recognises the link and imports from Spotify or YouTube. */
    fun import(link: String) {
        if (state.value is Progress.Working) return
        val spotifyId = if ("spotify" in link || Spotify.youtubePlaylistId(link) == null) Spotify.playlistId(link) else null
        val youtubeId = if (spotifyId == null) Spotify.youtubePlaylistId(link) else null
        if (spotifyId == null && youtubeId == null) {
            state.value = Progress.Failed("That link is not a Spotify or YouTube playlist. Copy the link from the playlist's Share button.")
            return
        }
        run {
            if (spotifyId != null) importSpotify(link) else importYouTube(youtubeId!!)
        }
    }

    /** Saves a YouTube Music playlist that is open in the app. */
    fun saveYouTube(playlistId: String) {
        if (state.value is Progress.Working) return
        run { importYouTube(playlistId) }
    }

    private fun run(block: suspend () -> Progress.Done) {
        state.value = Progress.Working("Reading the playlist")
        scope.launch {
            state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpotifyException) {
                Progress.Failed(e.message.orEmpty())
            } catch (e: ImportException) {
                Progress.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                Progress.Failed("Couldn't reach the playlist. Check your connection and try again.")
            } catch (e: Exception) {
                Progress.Failed("The playlist could not be imported: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun importYouTube(id: String): Progress.Done {
        val page = catalog.playlist(id) ?: throw ImportException("YouTube could not find this playlist. Check that it is public.")
        if (page.tracks.isEmpty()) throw ImportException("This playlist has no songs that can be played.")
        state.value = Progress.Working("Saving ${page.playlist.title}")
        return save("youtube", page.playlist.id, page.playlist.title, page.tracks, emptyList())
    }

    private suspend fun importSpotify(link: String): Progress.Done {
        val list = spotify.playlist(link)
        val done = AtomicInteger(0)
        val step = "Finding the songs on YouTube Music"
        state.value = Progress.Working(step, 0, list.songs.size)
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        val found = coroutineScope {
            list.songs.map { song ->
                async {
                    gate.withPermit {
                        // A recording the listener picked before for this song wins over a fresh search.
                        val pinned = library.dao.pinFor(Match.songKey(song.title, song.artist))?.videoId?.let { library.dao.song(it) }
                        val track = pinned?.toTrack()
                            ?: runCatching { catalog.resolve(song.title, song.artist, song.durationSec) }.getOrNull()?.track
                        state.value = Progress.Working(step, done.incrementAndGet(), list.songs.size)
                        song to track
                    }
                }
            }.awaitAll()
        }
        val tracks = found.mapNotNull { it.second }
        val unmatched = found.filter { it.second == null }.map { "${it.first.title} · ${it.first.artist}" }
        if (tracks.isEmpty()) throw ImportException("None of the songs in this playlist could be found on YouTube Music.")
        return save("spotify", list.id, list.name, tracks, unmatched)
    }

    private suspend fun save(source: String, sourceId: String, name: String, tracks: List<Track>, unmatched: List<String>): Progress.Done {
        val existing = library.importedPlaylist(source, sourceId)
        return if (existing != null) {
            val added = library.addToPlaylist(existing.id, tracks)
            Progress.Done(existing.id, existing.name, added, unmatched)
        } else {
            val id = library.createPlaylist(name, source = source, sourceId = sourceId)
            val added = library.addToPlaylist(id, tracks)
            Progress.Done(id, name, added, unmatched)
        }
    }

    private class ImportException(message: String) : Exception(message)

    private companion object {
        const val LOOKUPS_AT_ONCE = 4
    }
}
