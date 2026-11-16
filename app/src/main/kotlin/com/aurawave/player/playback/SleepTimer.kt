package com.aurawave.player.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the music should stop. The playback service carries it out; the screens set and show it. */
sealed interface Sleep {
    /** Stop at a time on the [SystemClock.elapsedRealtime] clock, fading out over the last 30 seconds. */
    data class At(val endsAtElapsed: Long) : Sleep {
        fun remainingMs(): Long = (endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    /** Stop when the playing song (or podcast episode) ends. */
    data object EndOfSong : Sleep

    /** Stop after this many more songs, counting the one playing now. */
    data class AfterSongs(val songs: Int) : Sleep
}

class SleepTimer {
    private val state = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = state.asStateFlow()

    fun inMinutes(minutes: Int) {
        state.value = Sleep.At(SystemClock.elapsedRealtime() + minutes * 60_000L)
    }

    fun endOfSong() {
        state.value = Sleep.EndOfSong
    }

    fun afterSongs(count: Int) {
        state.value = if (count <= 1) Sleep.EndOfSong else Sleep.AfterSongs(count)
    }

    fun cancel() {
        state.value = null
    }

    /** Used by the service as songs finish. */
    internal fun set(value: Sleep?) {
        state.value = value
    }

    companion object {
        const val FADE_MS = 30_000L
    }
}
