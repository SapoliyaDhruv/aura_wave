package com.aurawave.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurawave.innertube.Track
import com.google.common.util.concurrent.ListenableFuture

/**
 * The screens' handle on the player, which lives in [PlaybackService].
 * It mirrors the player's state into Compose state and forwards commands.
 */
@Stable
@OptIn(UnstableApi::class)
class PlayerConnection(private val context: Context) {
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var current by mutableStateOf<MediaItem?>(null)
        private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set

    /** True while the listener wants music, including while it is still loading. */
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Bumps whenever the position jumps (seek, song change), so progress bars re-read it. */
    var positionTick by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player, timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
            if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                positionTick++
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = describe(e)
        }
    }

    fun connect() {
        if (pending != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        pending = future
        future.addListener({
            if (pending !== future) return@addListener // disconnected meanwhile
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c, timelineChanged = true)
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller = null
        pending?.let(MediaController::releaseFuture)
        pending = null
    }

    private fun sync(player: Player, timelineChanged: Boolean) {
        current = player.currentMediaItem
        index = player.currentMediaItemIndex
        playing = !Util.shouldShowPlayButton(player)
        buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
        shuffle = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (timelineChanged) queue = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    }

    private fun describe(e: PlaybackException): String = when {
        e.isUnplayable() -> "This song can't be played right now. Skipping it."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "No connection. Music resumes when you're back online and press Play."
        else -> "This song stopped unexpectedly. Trying the next one."
    }

    fun clearError() {
        error = null
    }

    // ---------- commands ----------

    /** Replaces the queue with [tracks] and starts at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        error = null
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track))
        c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track))
        c.addMediaItem(track.toMediaItem())
    }

    fun togglePlay() {
        controller?.let { Util.handlePlayPauseButtonAction(it) }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Restarts the song, or goes to the previous one when pressed near the start. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun jumpTo(queueIndex: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(queueIndex)
        c.prepare()
        c.play()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
