package com.aurawave.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurawave.player.AuraApp
import com.aurawave.player.MainActivity
import com.aurawave.player.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player. Media3 provides what the old web player had to fake: the foreground
 * service and notification, the lock-screen and Bluetooth controls, audio focus (calls and
 * other apps pause the music, short interruptions resume it), pausing when headphones
 * are unplugged, and keeping the CPU and Wi-Fi awake only while music is playing.
 *
 * On top of that this service keeps the music going: it adds similar songs before the
 * queue runs out and moves past songs that cannot be played.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as AuraApp).graph

    /** Songs heard since the service started, so autoplay does not repeat them. */
    private val heard = LinkedHashSet<String>()
    private var consecutiveFailures = 0
    private var radioJob: Job? = null
    private var radioSeed: String? = null

    override fun onCreate() {
        super.onCreate()
        val mediaSources = DefaultMediaSourceFactory(streamDataSourceFactory(graph))
            .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_aurawave) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        graph.listenLog.songEnded()
        scope.cancel()
        session?.release()
        session = null
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withStreamUri() }.toMutableList())
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) {
                graph.listenLog.songEnded() // the queue was cleared
                return
            }
            heard += mediaItem.mediaId
            graph.listenLog.songStarted(mediaItem.toTrack(), player.isPlaying)
            extendQueueIfLow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = graph.listenLog.playingChanged(isPlaying)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveFailures = 0
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) extendQueueIfLow()
        }

        override fun onPlayerError(error: PlaybackException) = moveOn(error)
    }

    /**
     * A song failed after the player's own retries. Without a connection nothing else
     * will play either, so the song stays put and Play tries it again. Otherwise move to
     * the next song, and stop after three failures in a row so a wider outage (or a YouTube
     * change that breaks every song) does not burn through the whole queue.
     */
    private fun moveOn(error: PlaybackException) {
        val offline = !error.isUnplayable() && (
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        if (offline) return
        consecutiveFailures++
        if (consecutiveFailures >= MAX_FAILURES_IN_A_ROW || !player.hasNextMediaItem()) return
        player.seekToNextMediaItem()
        player.prepare()
    }

    /** Autoplay: when the queue is about to run out, append songs similar to the current one. */
    private fun extendQueueIfLow() {
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val low = if (player.shuffleModeEnabled) {
            !player.hasNextMediaItem()
        } else {
            player.mediaItemCount - player.currentMediaItemIndex - 1 <= 1
        }
        if (!low) return
        val seed = player.currentMediaItem?.mediaId ?: return
        if (radioJob?.isActive == true) {
            if (radioSeed == seed) return
            radioJob?.cancel() // the listener moved to another song: look for songs like that one instead
        }

        radioSeed = seed
        radioJob = scope.launch {
            val related = try {
                graph.catalog.radio(seed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // no connection or YouTube hiccup: the next song change tries again
            }
            // The listener may have started something else while this was loading.
            if (player.currentMediaItem?.mediaId != seed) return@launch
            val queued = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
            val fresh = related.filter { it.videoId !in queued && it.videoId !in heard }.take(AUTOPLAY_BATCH)
            if (fresh.isNotEmpty()) player.addMediaItems(fresh.map { it.toMediaItem() })
        }
    }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 3
        const val AUTOPLAY_BATCH = 15
    }
}
