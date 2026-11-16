package com.aurawave.player.data

import com.aurawave.innertube.Match
import com.aurawave.innertube.MusicLink
import com.aurawave.innertube.Spotify
import com.aurawave.innertube.SpotifyPlaylist
import com.aurawave.innertube.SpotifySong
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
 * Brings music in from a pasted link: a playlist or an album from Spotify, YouTube or
 * YouTube Music becomes a playlist in the library, and a single song is found so it can be
 * played or filed. Importing the same playlist again adds its new songs to the copy made
 * the first time.
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

        /** The link was one song, and this is it on YouTube Music. */
        data class Found(val track: Track) : Progress
        data class Failed(val message: String) : Progress
    }

    private val state = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = state.asStateFlow()

    fun dismiss() {
        if (state.value !is Progress.Working) state.value = Progress.Idle
    }

    /** Reads what the link names and brings it in. */
    fun import(link: String) {
        if (state.value is Progress.Working) return
        val what = MusicLink.parse(link)
        if (what == null) {
            state.value = Progress.Failed(
                "That link is not a song, an album or a playlist from Spotify, YouTube or YouTube Music. Copy the link from the Share button.",
            )
            return
        }
        run { open(what) }
    }

    private suspend fun open(what: MusicLink): Progress = when (what) {
        is MusicLink.Shortened -> {
            val real = spotify.expand(what.url) ?: throw ImportException("That short link did not lead to a song, an album or a playlist.")
            open(real)
        }
        is MusicLink.SpotifyPlaylist -> importSpotify(spotify.collection("playlist", what.id), what.id)
        is MusicLink.SpotifyAlbum -> importSpotify(spotify.collection("album", what.id), "album:${what.id}")
        is MusicLink.SpotifyTrack -> {
            state.value = Progress.Working("Finding the song on YouTube Music")
            val song = spotify.song(what.id)
            Progress.Found(find(song) ?: throw ImportException("“${song.title}” by ${song.artist} could not be found on YouTube Music."))
        }
        is MusicLink.YouTubePlaylist -> importYouTube(what.id)
        is MusicLink.YouTubeAlbum -> {
            val page = catalog.album(what.browseId) ?: throw ImportException("YouTube Music could not find this album.")
            if (page.tracks.isEmpty()) throw ImportException("This album has no songs that can be played.")
            state.value = Progress.Working("Saving ${page.album.title}")
            save("youtube", what.browseId, page.album.title, page.tracks, emptyList())
        }
        is MusicLink.YouTubeSong ->
            Progress.Found(catalog.song(what.videoId) ?: throw ImportException("YouTube Music has no song at this link."))
    }

    /** The recording for a song known by name: the one the listener picked before for it, or the best match. */
    private suspend fun find(song: SpotifySong): Track? {
        val pinned = library.dao.pinFor(Match.songKey(song.title, song.artist))?.videoId?.let { library.dao.song(it) }
        return pinned?.toTrack() ?: runCatching { catalog.resolve(song.title, song.artist, song.durationSec) }.getOrNull()?.track
    }

    /** Saves a YouTube Music playlist that is open in the app. */
    fun saveYouTube(playlistId: String) {
        if (state.value is Progress.Working) return
        run { importYouTube(playlistId) }
    }

    private fun run(block: suspend () -> Progress) {
        state.value = Progress.Working("Reading the link")
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
        // An album shared as a list ("OLAK…") comes without a title of its own: its songs name it.
        val title = page.playlist.title.takeUnless { it.isEmpty() || it == "Playlist" }
            ?: page.tracks.first().album.ifEmpty { "Imported playlist" }
        state.value = Progress.Working("Saving $title")
        return save("youtube", page.playlist.id.ifEmpty { id }, title, page.tracks, emptyList())
    }

    private suspend fun importSpotify(list: SpotifyPlaylist, sourceId: String): Progress.Done {
        val done = AtomicInteger(0)
        val step = "Finding the songs on YouTube Music"
        state.value = Progress.Working(step, 0, list.songs.size)
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        val found = coroutineScope {
            list.songs.map { song ->
                async {
                    gate.withPermit {
                        val track = find(song)
                        state.value = Progress.Working(step, done.incrementAndGet(), list.songs.size)
                        song to track
                    }
                }
            }.awaitAll()
        }
        val tracks = found.mapNotNull { it.second }
        val unmatched = found.filter { it.second == null }.map { "${it.first.title} · ${it.first.artist}" }
        if (tracks.isEmpty()) throw ImportException("None of the songs at this link could be found on YouTube Music.")
        return save("spotify", sourceId, list.name, tracks, unmatched)
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
