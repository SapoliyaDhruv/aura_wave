package com.aurawave.player.data

import android.os.SystemClock
import com.aurawave.innertube.Track
import com.aurawave.player.data.db.LibraryDao
import com.aurawave.player.data.db.LikeRow
import com.aurawave.player.data.db.PlayRow
import com.aurawave.player.data.db.SongRow
import com.aurawave.player.data.db.toRow
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The listener's own music: liked songs and listening history, stored on the device. */
class Library(private val dao: LibraryDao, private val scope: CoroutineScope) {

    val liked: Flow<List<Track>> = dao.liked().map { rows -> rows.map(SongRow::toTrack) }

    /** Ids of liked songs, kept ready so any row can show its heart without asking the database. */
    val likedIds: StateFlow<Set<String>> =
        dao.likedIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val recentlyPlayed: Flow<List<Track>> = dao.recentlyPlayed(100).map { rows -> rows.map(SongRow::toTrack) }

    fun setLiked(track: Track, liked: Boolean) {
        scope.launch {
            if (liked) {
                dao.upsertSong(track.toRow())
                dao.like(LikeRow(track.videoId, System.currentTimeMillis()))
            } else {
                dao.unlike(track.videoId)
            }
        }
    }

    internal suspend fun startPlay(track: Track): Long {
        dao.upsertSong(track.toRow())
        return dao.insertPlay(PlayRow(videoId = track.videoId, startedAt = System.currentTimeMillis(), listenedMs = 0, completed = false))
    }

    internal suspend fun savePlay(id: Long, listenedMs: Long, completed: Boolean) =
        dao.updatePlay(id, listenedMs, completed)
}

/**
 * Measures how long each song was really heard (paused time does not count) and
 * writes it to the library. The player service tells it what happens.
 */
class ListenLog(private val library: Library, private val scope: CoroutineScope) {
    private class Listen(val row: Deferred<Long>, val durationMs: Long) {
        var heardMs = 0L
        var playingSince = 0L // 0 while paused

        fun heardNow(): Long = heardMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0L
    }

    private var current: Listen? = null

    fun songStarted(track: Track, isPlaying: Boolean) {
        songEnded()
        val listen = Listen(scope.async { library.startPlay(track) }, track.durationSec * 1000L)
        if (isPlaying) listen.playingSince = SystemClock.elapsedRealtime()
        current = listen
    }

    fun playingChanged(isPlaying: Boolean) {
        val listen = current ?: return
        if (isPlaying) {
            if (listen.playingSince == 0L) listen.playingSince = SystemClock.elapsedRealtime()
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
        val heard = listen.heardMs
        // "Stayed to the end" allows for skipping an outro.
        val completed = listen.durationMs > 0 && heard >= listen.durationMs * 0.85
        scope.launch { runCatching { library.savePlay(listen.row.await(), heard, completed) } }
    }
}
