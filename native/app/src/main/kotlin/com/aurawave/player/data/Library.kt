package com.aurawave.player.data

import android.os.SystemClock
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistSummary
import com.aurawave.innertube.Match
import com.aurawave.player.data.db.FollowedArtistRow
import com.aurawave.player.data.db.FollowedPodcastRow
import com.aurawave.player.data.db.EpisodeProgressRow
import com.aurawave.innertube.PodcastRef
import com.aurawave.player.data.db.SavedAlbumRow
import com.aurawave.innertube.Track
import com.aurawave.player.data.db.HiddenRow
import com.aurawave.player.data.db.LibraryDao
import com.aurawave.player.data.db.LikeRow
import com.aurawave.player.data.db.PinRow
import com.aurawave.player.data.db.PlayRow
import com.aurawave.player.data.db.PlaylistRow
import com.aurawave.player.data.db.PlaylistSummary
import com.aurawave.player.data.db.SearchRow
import com.aurawave.player.data.db.SongPlays
import com.aurawave.player.data.db.SongPrefRow
import com.aurawave.player.data.db.SongRow
import com.aurawave.player.data.db.toRow
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The listener's own music, stored on the device: likes, playlists, history and what to keep out of recommendations. */
class Library(val dao: LibraryDao, private val scope: CoroutineScope) {

    /** Set by the YouTube Music account, to send the listener's changes there. */
    var onLikeChanged: ((Track, Boolean) -> Unit)? = null
    var onSongsAdded: ((playlistId: Long, tracks: List<Track>) -> Unit)? = null
    var onPlayStarted: ((Track) -> Unit)? = null

    // ---------- likes ----------

    val liked: Flow<List<Track>> = dao.liked().map { rows -> rows.map(SongRow::toTrack) }

    /** Ids of liked songs, kept ready so any row can show its heart without asking the database. */
    val likedIds: StateFlow<Set<String>> =
        dao.likedIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setLiked(track: Track, liked: Boolean) {
        scope.launch {
            if (liked) {
                dao.upsertSong(track.toRow())
                dao.like(LikeRow(track.videoId, System.currentTimeMillis()))
            } else {
                dao.unlike(track.videoId)
            }
            onLikeChanged?.invoke(track, liked)
        }
    }

    // ---------- history ----------

    val recentlyPlayed: Flow<List<Track>> = dao.recentlyPlayed(100).map { rows -> rows.map(SongRow::toTrack) }
    val playCount: Flow<Int> = dao.playCount()

    fun topSongs(since: Long, limit: Int = 50): Flow<List<SongPlays>> = dao.topSongs(since, limit)
    fun listenedSince(since: Long): Flow<Long> = dao.listenedSince(since)
    suspend fun songPlaysSince(since: Long): List<SongPlays> = dao.songPlaysSince(since)

    fun clearHistory() {
        scope.launch { dao.clearPlays() }
    }

    internal suspend fun startPlay(track: Track): Long {
        dao.upsertSong(track.toRow())
        onPlayStarted?.invoke(track)
        return dao.insertPlay(PlayRow(videoId = track.videoId, startedAt = System.currentTimeMillis(), listenedMs = 0, completed = false))
    }

    internal suspend fun savePlay(id: Long, listenedMs: Long, completed: Boolean) =
        dao.updatePlay(id, listenedMs, completed)

    // ---------- playlists ----------

    val playlists: Flow<List<PlaylistSummary>> = dao.playlists()

    fun playlist(id: Long): Flow<PlaylistRow?> = dao.playlist(id)

    fun playlistSongs(id: Long): Flow<List<Track>> = dao.playlistSongs(id).map { rows -> rows.map(SongRow::toTrack) }

    fun playlistsContaining(videoId: String): Flow<Set<Long>> = dao.playlistsContaining(videoId).map { it.toSet() }

    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList(), source: String = "you", sourceId: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertPlaylist(
            PlaylistRow(name = name.trim().ifEmpty { "New playlist" }, source = source, sourceId = sourceId, createdAt = now, updatedAt = now),
        )
        if (tracks.isNotEmpty()) addToPlaylist(id, tracks)
        return id
    }

    /** Adds the songs that are not in the playlist yet and returns how many were new. */
    suspend fun addToPlaylist(id: Long, tracks: List<Track>): Int {
        dao.upsertSongs(tracks.map { it.toRow() })
        val added = dao.appendToPlaylist(id, tracks.map { it.videoId }, System.currentTimeMillis())
        if (added > 0) onSongsAdded?.invoke(id, tracks)
        return added
    }

    fun removeFromPlaylist(id: Long, videoId: String) {
        scope.launch { dao.removeFromPlaylist(id, videoId) }
    }

    fun reorderPlaylist(id: Long, videoIds: List<String>) {
        scope.launch { dao.reorderPlaylist(id, videoIds, System.currentTimeMillis()) }
    }

    fun renamePlaylist(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        scope.launch { dao.renamePlaylist(id, clean, System.currentTimeMillis()) }
    }

    fun deletePlaylist(id: Long) {
        scope.launch { dao.deletePlaylist(id) }
    }

    // ---------- kept out of recommendations ----------

    val hidden: StateFlow<List<HiddenRow>> = dao.hidden().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun hideSong(track: Track) {
        scope.launch { dao.hide(HiddenRow(Match.songKey(track), KIND_SONG, "${track.title} · ${track.artist}", System.currentTimeMillis())) }
    }

    fun hideArtist(name: String) {
        val key = artistKey(name)
        if (key.isEmpty()) return
        scope.launch { dao.hide(HiddenRow(key, KIND_ARTIST, name, System.currentTimeMillis())) }
    }

    fun resetHidden() {
        scope.launch { dao.clearHidden() }
    }

    fun isHidden(track: Track, rows: List<HiddenRow> = hidden.value): Boolean {
        if (rows.isEmpty()) return false
        val songKey = Match.songKey(track)
        val artists = Match.splitArtists(track.artist).map(Match::fold).toSet()
        return rows.any { (it.kind == KIND_SONG && it.key == songKey) || (it.kind == KIND_ARTIST && it.key in artists) }
    }

    // ---------- the listener's choice of recording ----------

    suspend fun pinnedFor(track: Track): String? = dao.pinFor(Match.songKey(track))?.videoId

    /** Remembers [chosen] as the right recording of [track], and swaps it in wherever the listener kept the song. */
    suspend fun pin(track: Track, chosen: Track) {
        dao.upsertSong(chosen.toRow())
        dao.pin(PinRow(Match.songKey(track), chosen.videoId, System.currentTimeMillis()))
        dao.replaceSong(track.videoId, chosen.toRow())
    }

    // ---------- recent searches ----------

    val recentSearches: Flow<List<String>> = dao.recentSearches(MAX_SEARCHES)

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            dao.saveSearch(SearchRow(q.lowercase(), q, System.currentTimeMillis()))
            dao.trimSearches(MAX_SEARCHES)
        }
    }

    fun removeSearch(query: String) {
        scope.launch { dao.deleteSearch(query.trim().lowercase()) }
    }

    fun clearSearches() {
        scope.launch { dao.clearSearches() }
    }

    // ---------- saved albums, followed artists ----------

    val savedAlbums: Flow<List<AlbumRef>> = dao.savedAlbums().map { rows ->
        rows.map { AlbumRef(it.id, it.title, it.artist, it.year, it.coverUrl, it.type) }
    }
    val savedAlbumIds: StateFlow<Set<String>> =
        dao.savedAlbums().map { rows -> rows.mapTo(HashSet()) { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setAlbumSaved(album: AlbumRef, saved: Boolean) {
        scope.launch {
            if (saved) {
                dao.saveAlbum(SavedAlbumRow(album.id, album.title, album.artist, album.year, album.coverUrl, album.type, System.currentTimeMillis()))
            } else {
                dao.unsaveAlbum(album.id)
            }
        }
    }

    val followedArtists: Flow<List<ArtistSummary>> = dao.followedArtists().map { rows ->
        rows.map { ArtistSummary(it.id, it.name, "", it.imageUrl) }
    }
    val followedIds: StateFlow<Set<String>> =
        dao.followedArtists().map { rows -> rows.mapTo(HashSet()) { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setFollowing(id: String, name: String, imageUrl: String, follow: Boolean) {
        if (id.isEmpty()) return
        scope.launch {
            if (follow) dao.follow(FollowedArtistRow(id, name, imageUrl, System.currentTimeMillis())) else dao.unfollow(id)
        }
    }

    /** A playlist imported from Spotify or YouTube before, so importing it again updates it instead of making a copy. */
    suspend fun importedPlaylist(source: String, sourceId: String): PlaylistRow? = dao.playlistFromSource(source, sourceId)

    // ---------- podcasts ----------

    val followedPodcasts: Flow<List<PodcastRef>> = dao.followedPodcasts().map { rows ->
        rows.map { PodcastRef(it.id, it.title, it.author, it.coverUrl) }
    }
    val followedPodcastIds: StateFlow<Set<String>> =
        dao.followedPodcasts().map { rows -> rows.mapTo(HashSet()) { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setFollowingPodcast(show: PodcastRef, follow: Boolean) {
        scope.launch {
            if (follow) {
                dao.followPodcast(FollowedPodcastRow(show.id, show.title, show.author, show.coverUrl, System.currentTimeMillis()))
            } else {
                dao.unfollowPodcast(show.id)
            }
        }
    }

    /** Progress of every episode the listener started, by video id. */
    val episodeProgress: StateFlow<Map<String, EpisodeProgressRow>> =
        dao.allProgress().map { rows -> rows.associateBy { it.videoId } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val inProgress: Flow<List<Pair<Track, Float>>> = dao.inProgress(20).map { rows ->
        rows.map { it.song.toTrack() to if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f }
    }

    suspend fun progressOf(videoId: String): EpisodeProgressRow? = dao.progress(videoId)

    /** Saves where the listener is in an episode; past 95% it counts as played. */
    suspend fun saveProgress(episode: Track, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        dao.upsertSong(episode.toRow())
        val played = positionMs >= durationMs * 0.95
        dao.saveProgress(EpisodeProgressRow(episode.videoId, episode.albumId, if (played) 0 else positionMs, durationMs, played, System.currentTimeMillis()))
    }

    fun setPlayed(episode: Track, played: Boolean) {
        scope.launch {
            dao.upsertSong(episode.toRow())
            val old = dao.progress(episode.videoId)
            dao.saveProgress(
                EpisodeProgressRow(
                    videoId = episode.videoId,
                    podcastId = episode.albumId,
                    positionMs = 0,
                    durationMs = old?.durationMs ?: episode.durationSec * 1000L,
                    played = played,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ---------- per-song settings ----------

    fun lyricsOffset(videoId: String): Flow<Long> = dao.songPref(videoId).map { it?.lyricsOffsetMs ?: 0L }

    fun setLyricsOffset(videoId: String, offsetMs: Long) {
        scope.launch { dao.saveSongPref(SongPrefRow(videoId, offsetMs)) }
    }

    suspend fun lyricsOffsetNow(videoId: String): Long = lyricsOffset(videoId).first()

    companion object {
        const val KIND_SONG = "song"
        const val KIND_ARTIST = "artist"
        private const val MAX_SEARCHES = 12

        fun artistKey(name: String): String = Match.fold(Match.splitArtists(name).firstOrNull().orEmpty())
    }
}

/**
 * Measures how long each song was really heard (paused time does not count) and
 * writes it to the library. The player service tells it what happens.
 */
class ListenLog(private val library: Library, private val scope: CoroutineScope) {
    private inner class Listen(val track: Track) {
        val durationMs = track.durationSec * 1000L
        var heardMs = 0L
        var playingSince = 0L // 0 while paused

        /** Written when the song first actually plays: a queue restored at launch is not a listen. */
        var row: Deferred<Long>? = null

        fun heardNow(): Long = heardMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0L

        fun startPlaying() {
            if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
            if (row == null) row = scope.async { library.startPlay(track) }
        }
    }

    private var current: Listen? = null

    fun songStarted(track: Track, isPlaying: Boolean) {
        songEnded()
        val listen = Listen(track)
        if (isPlaying) listen.startPlaying()
        current = listen
    }

    fun playingChanged(isPlaying: Boolean) {
        val listen = current ?: return
        if (isPlaying) {
            listen.startPlaying()
        } else if (listen.playingSince != 0L) {
            listen.heardMs = listen.heardNow()
            listen.playingSince = 0L
            save(listen) // a pause may be the last thing that happens before the app is closed
        }
    }

    fun songEnded() {
        val listen = current ?: return
        current = null
        listen.heardMs = listen.heardNow()
        listen.playingSince = 0L
        save(listen)
    }

    private fun save(listen: Listen) {
        val row = listen.row ?: return
        val heard = listen.heardMs
        // "Stayed to the end" allows for skipping an outro.
        val completed = listen.durationMs > 0 && heard >= listen.durationMs * 0.85
        scope.launch { runCatching { library.savePlay(row.await(), heard, completed) } }
    }
}
