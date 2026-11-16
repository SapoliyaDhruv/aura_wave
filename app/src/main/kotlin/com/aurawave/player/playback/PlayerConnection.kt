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

    /** False while the app is off screen and for a moment after it returns; the state above is then stale. */
    val connected: Boolean get() = controller != null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player, timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
            if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                positionTick++
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            // A music video that will not play is put aside and its song carries on (see PlaybackService.moveOn).
            val watchingVideo = controller?.currentMediaItem?.video != null
            error = if (watchingVideo && !e.isOffline()) "This video can't be played here. Playing the song instead." else describe(e)
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
        // Before the player has opened the song (the app just started, with the last song waiting),
        // it does not know the length; the catalogue's figure keeps the progress line where it was.
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: (current?.toTrack()?.durationSec ?: 0) * 1000L
        if (timelineChanged) queue = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    }

    private fun PlaybackException.isOffline(): Boolean = !isUnplayable() && (
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )

    private fun describe(e: PlaybackException): String = when {
        e.isUnplayable() -> "This song can't be played right now. Skipping it."
        e.isOffline() -> "No connection. Music resumes when you're back online and press Play."
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

    fun addToQueue(track: Track) = addToQueue(listOf(track))

    /** Adds songs to the end of the queue in one step, however many there are. */
    fun addToQueue(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) return play(tracks)
        c.addMediaItems(tracks.map { it.toMediaItem() })
    }

    /** Moves a song in the queue. */
    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
    }

    fun remove(queueIndex: Int) {
        val c = controller ?: return
        if (queueIndex in 0 until c.mediaItemCount) c.removeMediaItem(queueIndex)
    }

    /** Removes everything after the playing song. */
    fun clearUpcoming() {
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    /** Swaps the playing song for another recording of it, starting at [positionMs]. */
    fun replaceCurrent(track: Track, positionMs: Long = 0) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track))
        val index = c.currentMediaItemIndex
        val wasPlaying = c.playWhenReady
        c.replaceMediaItem(index, track.toMediaItem())
        if (positionMs > 0) c.seekTo(index, positionMs)
        c.prepare()
        if (wasPlaying || positionMs == 0L) c.play()
    }

    /**
     * The Song/Video switch: streams the playing song from its music video [videoId] (or from
     * the song's own recording again, when null), carrying on from the same place. The queue
     * still holds the song, so nothing else about it changes.
     */
    fun streamVideo(videoId: String?, lengthMs: Long = 0) {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        if (item.video == videoId) return
        val index = c.currentMediaItemIndex
        // A video is often cut longer or shorter than its song. A place past the end of the
        // recording being switched to ([lengthMs], when known) would end the song on the spot,
        // so from there it starts over instead.
        val position = c.currentPosition.let { if (lengthMs > 0 && it > lengthMs - END_MARGIN_MS) 0L else it }
        c.replaceMediaItem(index, item.withVideo(videoId))
        c.seekTo(index, position)
        c.prepare()
    }

    /** What was playing when the listener stopped everything, so it can be put back. */
    class Stopped(val items: List<MediaItem>, val index: Int, val positionMs: Long, val wasPlaying: Boolean)

    /** Stops the music and empties the queue, which also takes the mini player away. Null when nothing was queued. */
    fun stopAll(): Stopped? {
        val c = controller ?: return null
        if (c.mediaItemCount == 0) return null
        val stopped = Stopped(List(c.mediaItemCount) { c.getMediaItemAt(it) }, c.currentMediaItemIndex, c.currentPosition, c.playWhenReady)
        c.pause()
        c.clearMediaItems()
        return stopped
    }

    /** Undoes [stopAll]: the same queue, at the same place. */
    fun restore(stopped: Stopped) {
        val c = controller ?: return
        error = null
        c.setMediaItems(stopped.items, stopped.index, stopped.positionMs)
        c.prepare()
        if (stopped.wasPlaying) c.play()
    }

    private companion object {
        const val END_MARGIN_MS = 8_000L
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

    /** Jumps back or ahead within the playing episode or song. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        c.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs - 1000) else target)
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
