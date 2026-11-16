package com.aurawave.player.data

import com.aurawave.innertube.AlbumPage
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistPage
import com.aurawave.innertube.ArtistSummary
import com.aurawave.innertube.PlaylistPage
import com.aurawave.innertube.PlaylistRef
import com.aurawave.innertube.PodcastRef
import com.aurawave.innertube.PodcastPage
import com.aurawave.innertube.Shelf
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.Resolution
import com.aurawave.innertube.SearchResult
import com.aurawave.innertube.Track
import com.aurawave.innertube.Match
import com.aurawave.innertube.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
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
        // YouTube's answers are large; reading them must not hold up the screen that asked.
        val value = withContext(Dispatchers.Default) { load() }
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

    /** One recording by its id (a pasted YouTube link). */
    suspend fun song(videoId: String): Track? = remembered("song:$videoId", 60 * MINUTE) { tube.song(videoId) }

    suspend fun artist(id: String, name: String): ArtistPage? =
        remembered("artist:${id.ifEmpty { name.lowercase() }}", 60 * MINUTE) { tube.artist(id, name) }

    suspend fun searchSongsOnly(query: String): List<Track> =
        remembered("songs:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchSongs(query.trim()) }

    suspend fun searchVideos(query: String): List<Track> =
        remembered("videos:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchSongs(query.trim(), InnerTube.FILTER_VIDEOS) }

    suspend fun searchAlbums(query: String): List<AlbumRef> =
        remembered("albums:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchAlbums(query.trim()) }

    suspend fun searchArtists(query: String): List<ArtistSummary> =
        remembered("artists:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchArtists(query.trim(), 20) }

    suspend fun searchPlaylists(query: String): List<PlaylistRef> =
        remembered("playlists:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchPlaylists(query.trim()) }

    suspend fun searchPodcasts(query: String): List<PodcastRef> =
        remembered("podcasts:${query.trim().lowercase()}", 30 * MINUTE) { tube.searchPodcasts(query.trim()) }

    suspend fun album(id: String): AlbumPage? = remembered("album:$id", 6 * 60 * MINUTE) { tube.album(id) }

    suspend fun playlist(id: String): PlaylistPage? = remembered("playlist:$id", 30 * MINUTE) { tube.playlist(id) }

    suspend fun artistReleases(browseId: String, params: String): List<AlbumRef> =
        remembered("releases:$browseId:$params", 6 * 60 * MINUTE) { tube.artistReleases(browseId, params) }

    suspend fun podcast(id: String): PodcastPage? = remembered("podcast:$id", 30 * MINUTE) { tube.podcast(id) }

    /** A show's newest episodes only: one request, where the whole show can take many. */
    suspend fun latestEpisodes(id: String): List<Episode> {
        memory["podcast:$id"]?.takeIf { System.currentTimeMillis() - it.at < 30 * MINUTE }?.let { cached ->
            (cached.value as? PodcastPage)?.let { return it.episodes.take(LATEST_EPISODES) }
        }
        return remembered("podcast-latest:$id", 30 * MINUTE) {
            tube.podcast(id, maxEpisodes = LATEST_EPISODES)?.episodes.orEmpty().take(LATEST_EPISODES)
        }
    }

    /** Explore, charts, moods and genres, new releases, a mood's playlists. */
    suspend fun shelves(browseId: String, params: String = ""): List<Shelf> =
        remembered("shelves:$browseId:$params", 60 * MINUTE) { tube.shelves(browseId, params) }

    /**
     * The official music video of a song, when YouTube Music has one that matches it. The answer
     * is kept, so a song is paired with the same video each time the listener switches to Video.
     */
    suspend fun musicVideoFor(track: Track): Track? =
        runCatching { remembered("video-of:${track.videoId}", 6 * 60 * MINUTE) { tube.musicVideoFor(track) } }.getOrNull()

    /** Other recordings of the same song, best match first, for "Not the right song?". */
    suspend fun alternatives(track: Track): List<Track> = coroutineScope {
        val lead = track.artists.firstOrNull()?.name ?: track.artist.split(',', '&').first().trim()
        val q = "${track.title} $lead".trim()
        val songs = async { runCatching { searchSongsOnly(q) }.getOrDefault(emptyList()) }
        val videos = async { runCatching { searchVideos(q) }.getOrDefault(emptyList()) }
        val byTitle = async { runCatching { searchSongsOnly(track.title) }.getOrDefault(emptyList()) }
        val wanted = Match.versionFlags(track.title)
        val want = Match.Want(track.title, track.artist, track.durationSec)
        (songs.await() + videos.await() + byTitle.await())
            .filter { it.videoId != track.videoId }
            .distinctBy { it.videoId }
            .filter { Match.titleSimilarity(track.title, it.title) >= 0.5 }
            .filter { t -> Match.versionFlags(t.title).all { it in wanted || it !in Match.HIDDEN_VERSIONS } }
            .sortedByDescending { Match.scoreCandidate(want, Match.Candidate(it.title, it.artist, it.album, it.durationSec, it.kind, channel = it.artist)).score }
            .take(12)
    }

    /** The exact recording for a song known only by its title and artist (imports), or no track. */
    suspend fun resolve(title: String, artist: String, durationSec: Int = 0): Resolution =
        remembered("resolve:${title.lowercase()}:${artist.lowercase()}:${durationSec / 5}", 24 * 60 * MINUTE) {
            tube.resolve(title, artist, durationSec)
        }

    private companion object {
        const val MINUTE = 60_000L
        const val MAX_ENTRIES = 200
        const val LATEST_EPISODES = 5
    }
}
