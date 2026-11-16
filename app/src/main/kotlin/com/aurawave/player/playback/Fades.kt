package com.aurawave.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player as the notification, lock screen, headphones and the app see it. When fades
 * are on, play fades in, pause fades out before pausing, and skipping dips the volume
 * briefly, so nothing starts or stops with a click.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(
    private val exo: ExoPlayer,
    private val sound: SoundEngine,
    private val enabled: () -> Boolean,
) : ForwardingPlayer(exo) {
    /** A pause or skip waiting for its fade-out to finish. */
    private var pending: (() -> Unit)? = null
    private var pendingIsSkip = false

    /** True while a pause or skip waits for its fade-out. */
    val busy: Boolean get() = pending != null

    private fun flushPending() {
        val action = pending ?: return
        pending = null
        pendingIsSkip = false
        action()
    }

    override fun play() {
        if (pendingIsSkip) flushPending() // a skip still happens
        pending = null // a pause that was fading out is called off
        if (!enabled()) {
            sound.setFade(1f)
            exo.play()
            return
        }
        if (!exo.isPlaying) sound.setFade(0f)
        exo.play()
        sound.fadeTo(1f, FADE_IN_MS)
    }

    override fun pause() {
        if (pendingIsSkip) flushPending()
        if (!enabled() || !exo.isPlaying) {
            pending = null
            sound.setFade(1f)
            exo.pause()
            return
        }
        pending = {
            exo.pause()
            sound.setFade(1f)
        }
        sound.fadeTo(0f, FADE_OUT_MS) { flushPending() }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, listOf(mediaItem))

    /**
     * "Play next" while shuffling. The player would give new songs a random place in its
     * shuffled order; songs put right after the playing one are meant to play right after it.
     */
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val playNext = exo.shuffleModeEnabled && mediaItems.isNotEmpty() && index == exo.currentMediaItemIndex + 1
        if (!playNext) {
            exo.addMediaItems(index, mediaItems)
            return
        }
        val before = shuffledOrder()
        val current = exo.currentMediaItemIndex
        exo.addMediaItems(index, mediaItems)
        // The songs that were at or after the insertion point moved up by the number added.
        val order = before.mapTo(ArrayList()) { if (it >= index) it + mediaItems.size else it }
        order.addAll(order.indexOf(current) + 1, (index until index + mediaItems.size).toList())
        if (order.size == exo.mediaItemCount) exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    /** The queue positions in the order shuffle plays them. */
    private fun shuffledOrder(): List<Int> {
        val timeline = exo.currentTimeline
        val order = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    override fun seekToNext() = skip { exo.seekToNext() }

    override fun seekToNextMediaItem() = skip { exo.seekToNextMediaItem() }

    override fun seekToPrevious() = skip { exo.seekToPrevious() }

    override fun seekToPreviousMediaItem() = skip { exo.seekToPreviousMediaItem() }

    private fun skip(action: () -> Unit) {
        flushPending() // pressing Next twice quickly still skips twice
        if (!enabled() || !exo.isPlaying) {
            action()
            return
        }
        pending = action
        pendingIsSkip = true
        sound.fadeTo(0f, SKIP_OUT_MS) {
            flushPending()
            sound.fadeTo(1f, SKIP_IN_MS)
        }
    }

    private companion object {
        const val FADE_IN_MS = 400L
        const val FADE_OUT_MS = 250L
        const val SKIP_OUT_MS = 120L
        const val SKIP_IN_MS = 300L
    }
}

/**
 * Overlaps the end of one song with the start of the next. The main player moves on to
 * the next song and fades it in, while a short-lived second player plays the last
 * seconds of the outgoing song (from the disk cache) and fades it out. The playback
 * session never changes players, so the notification and controls stay put.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val player: ExoPlayer,
    private val sources: MediaSource.Factory,
    private val sound: SoundEngine,
    private val seconds: () -> Int,
    private val scope: CoroutineScope,
    /** True while the listener's own pause or skip is fading: no crossfade then. */
    private val listenerBusy: () -> Boolean,
    /** Told just before the crossfade moves to the next song, which counts as the song ending on its own. */
    private val onHandOver: () -> Unit,
) {
    private var watchJob: Job? = null
    private var crossJob: Job? = null
    private var tail: ExoPlayer? = null
    private var active = false

    fun playingChanged(isPlaying: Boolean) {
        if (isPlaying) watch() else watchJob?.cancel()
    }

    /**
     * The outgoing tail follows play and pause, not "is playing": the main player briefly
     * buffers right after the hand-over, and the tail must not stop for that.
     */
    fun playWhenReadyChanged(playWhenReady: Boolean) {
        if (handedOver) tail?.playWhenReady = playWhenReady
    }

    private var handedOver = false

    private fun stillWanted(): Boolean =
        player.hasNextMediaItem() && player.repeatMode != Player.REPEAT_MODE_ONE && !player.pauseAtEndOfMediaItems && !listenerBusy()

    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val overlap = seconds() * 1000L
                if (overlap <= 0 || active) continue
                val duration = player.duration
                if (duration == C.TIME_UNSET || duration < overlap * 3) continue
                val remaining = duration - player.currentPosition
                if (remaining > overlap + LEAD_MS || remaining < MIN_REMAINING_MS) continue
                if (!player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE || player.pauseAtEndOfMediaItems) continue
                active = true
                // Its own job: pausing stops this watch, but a crossfade under way just waits.
                crossJob = scope.launch { crossfade(overlap) }
            }
        }
    }

    private suspend fun crossfade(overlap: Long) {
        val item = player.currentMediaItem ?: run { active = false; return }
        handedOver = false
        try {
            // Start the outgoing song's tail a moment ahead, paused, so it is ready when the main player gets there.
            val handOver = player.currentPosition + LEAD_MS
            val t = ExoPlayer.Builder(context)
                .setMediaSourceFactory(sources)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                .build()
            tail = t
            t.setAudioSessionId(player.audioSessionId) // the equaliser applies to both songs
            t.playbackParameters = player.playbackParameters
            t.volume = 0f
            t.setMediaItem(item, handOver)
            t.prepare()
            val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (t.playbackState != Player.STATE_READY) delay(20)
                true
            }
            if (ready == null || player.currentMediaItem?.mediaId != item.mediaId) return // not ready in time: songs simply follow each other
            while (player.currentPosition < handOver && player.currentMediaItem?.mediaId == item.mediaId) {
                if (!player.playWhenReady) return // paused while waiting: let the songs follow each other normally
                delay(10)
            }
            // Things may have changed while waiting: a sleep timer set, the queue cleared, a skip pressed.
            if (player.currentMediaItem?.mediaId != item.mediaId || !player.isPlaying || !stillWanted()) return

            sound.setTail(t, 1f)
            t.play()
            sound.setCrossfade(0f)
            handedOver = true
            onHandOver()
            player.seekToNextMediaItem()
            val length = (player.duration.takeIf { it != C.TIME_UNSET }?.let { minOf(overlap, it) } ?: overlap) - LEAD_MS
            var elapsed = 0L
            while (elapsed < length) {
                if (!player.playWhenReady) {
                    delay(PAUSED_STEP_MS) // paused mid-crossfade: both wait
                    continue
                }
                delay(STEP_MS)
                elapsed += STEP_MS
                val f = (elapsed.toFloat() / length).coerceIn(0f, 1f)
                sound.setCrossfade(f)
                sound.setTail(t, (1f - f) * (1f - f)) // the old song bows out a little faster
            }
        } finally {
            if (handedOver) sound.setCrossfade(1f)
            sound.setTail(null)
            handedOver = false
            tail?.release()
            tail = null
            active = false
        }
    }

    fun release() {
        watchJob?.cancel()
        crossJob?.cancel()
        tail?.release()
        tail = null
    }

    private companion object {
        const val POLL_MS = 200L
        const val LEAD_MS = 600L
        const val MIN_REMAINING_MS = 1500L
        const val READY_TIMEOUT_MS = 2500L
        const val STEP_MS = 50L
        const val PAUSED_STEP_MS = 250L
    }
}
