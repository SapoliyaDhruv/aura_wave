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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.aurawave.player.AuraApp
import com.aurawave.player.MainActivity
import com.aurawave.player.R
import com.google.common.util.concurrent.Futures
import com.aurawave.innertube.Match
import com.aurawave.innertube.Kind
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.guava.future
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the player. Media3 provides what the old web player had to fake: the foreground
 * service and notification, the lock-screen and Bluetooth controls, audio focus (calls and
 * other apps pause the music, short interruptions resume it), pausing when headphones
 * are unplugged, and keeping the CPU and Wi-Fi awake only while music is playing.
 *
 * On top of that this service keeps the music going: it adds similar songs before the
 * queue runs out and moves past songs that cannot be played. It also carries out the
 * sleep timer and remembers the queue, so the app reopens where the listener left off.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var sound: SoundEngine
    private lateinit var crossfader: Crossfader
    private lateinit var fading: FadingPlayer

    /** Set by a crossfade just before it moves on: that song change counts as the song ending on its own. */
    private var crossfadeEnding = false
    private var session: MediaLibrarySession? = null
    private val tree by lazy { MediaTree(graph) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as AuraApp).graph

    /** Songs heard since the service started, so autoplay does not repeat them. */
    private val heard = LinkedHashSet<String>()
    private var consecutiveFailures = 0
    private var radioJob: Job? = null
    private var radioSeed: String? = null
    private var sleepJob: Job? = null
    /** The episode playing, so it can be marked played when it runs to its end. */
    private var episodePlaying: com.aurawave.innertube.Track? = null
    private var placeJob: Job? = null

    /** Queue files are written one at a time, off the main thread. */
    private val disk = Dispatchers.IO.limitedParallelism(1)

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
        sound = SoundEngine(player, graph.settings, graph.loudness, graph.sound, scope)
        sound.start()
        crossfader = Crossfader(
            this, player, mediaSources, sound, { graph.settings.crossfadeSec.value }, scope,
            listenerBusy = { fading.busy },
            onHandOver = { crossfadeEnding = true },
        )

        restoreQueue()
        player.addListener(playerListener)
        updateWidget()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Commands from the notification, headphones and the app go through the fading wrapper.
        fading = FadingPlayer(player, sound) { graph.settings.fades.value }
        session = MediaLibrarySession.Builder(this, fading, sessionCallback)
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_aurawave) },
        )

        scope.launch { graph.sleepTimer.sleep.collect(::applySleep) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        if (songsJob?.isActive == true) writeSongs() // a queue change still waiting to be written
        savePlace()
        graph.sleepTimer.cancel()
        graph.listenLog.songEnded()
        scope.cancel()
        session?.release()
        session = null
        crossfader.release()
        sound.release()
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { prepareItems(mediaItems) }

        /** A song tapped in Android Auto plays with the rest of its list after it. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val expanded = mediaItems.singleOrNull()?.let { tree.expand(it) }
            if (expanded != null) {
                val (tracks, index) = expanded
                MediaItemsWithStartPosition(prepareItems(tracks.map { it.toMediaItem() }), index, C.TIME_UNSET)
            } else {
                MediaItemsWithStartPosition(prepareItems(mediaItems), startIndex, startPositionMs)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(if (params?.isRecent == true) tree.recentRoot() else tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = runCatching { tree.children(parentId) }.getOrDefault(emptyList())
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val song = graph.library.dao.song(MediaTree.videoIdOf(mediaId))
            if (song != null) LibraryResult.ofItem(song.toTrack().toMediaItem(), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = runCatching { tree.search(query).size }.getOrDefault(0)
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        /** Play pressed on headphones or a car after the app was closed (even after a reboot): pick up the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val saved = graph.queueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs),
            )
        }
    }

    /**
     * Items from a controller: fills in details that did not travel with them (Android Auto
     * sends only ids), swaps in recordings the listener pinned, and gives each its stream address.
     */
    private suspend fun prepareItems(mediaItems: List<MediaItem>): MutableList<MediaItem> {
        // "Not the right song?": a recording the listener picked for a song replaces the one asked for.
        val pins = runCatching { graph.library.dao.allPins() }.getOrDefault(emptyList()).associate { it.songKey to it.videoId }
        return mediaItems.map { asked ->
            val id = MediaTree.videoIdOf(asked.mediaId)
            val item = if (asked.mediaMetadata.title == null || id != asked.mediaId) {
                graph.library.dao.song(id)?.toTrack()?.toMediaItem() ?: asked.buildUpon().setMediaId(id).build()
            } else {
                asked
            }
            if (item.toTrack().kind == Kind.VIDEO) return@map item
            val pinned = pins[Match.songKey(item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString())]
                ?.takeIf { it != item.mediaId }
                ?.let { graph.library.dao.song(it) }
            pinned?.toTrack()?.toMediaItem() ?: item
        }.map { it.withStreamUri() }.toMutableList()
    }

    private var widgetJob: Job? = null

    /** Redraws the home-screen widget with the playing song. */
    private fun updateWidget() {
        val item = player.currentMediaItem
        val meta = item?.mediaMetadata
        val playing = player.playWhenReady
        widgetJob?.cancel()
        widgetJob = scope.launch {
            runCatching {
                PlayerWidget.update(
                    applicationContext,
                    title = meta?.title?.toString() ?: "AuraWave",
                    artist = meta?.artist?.toString().orEmpty(),
                    playing = playing,
                    coverUrl = meta?.artworkUri?.toString().orEmpty(),
                )
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                updateWidget()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) {
                graph.listenLog.songEnded() // the queue was cleared
                return
            }
            heard += mediaItem.mediaId
            val track = mediaItem.toTrack()
            val finished = episodePlaying
            val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || crossfadeEnding
            crossfadeEnding = false
            if (finished != null && ended) graph.library.setPlayed(finished, true)
            episodePlaying = track.takeIf { it.kind == Kind.EPISODE }
            sound.playingEpisode(track.kind == Kind.EPISODE)
            if (track.kind == Kind.EPISODE) resumeEpisode(track)
            sound.levelFor(mediaItem.mediaId)
            prefetchNext()
            graph.listenLog.songStarted(mediaItem.toTrack(), player.isPlaying)
            countSongForSleep(reason)
            extendQueueIfLow()
            savePlace()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = sound.attach(audioSessionId)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            graph.listenLog.playingChanged(isPlaying)
            crossfader.playingChanged(isPlaying)
            if (isPlaying) {
                placeJob?.cancel()
                placeJob = scope.launch {
                    while (isActive) {
                        delay(PLACE_SAVE_INTERVAL_MS)
                        savePlace()
                    }
                }
            } else {
                placeJob?.cancel()
                savePlace()
            }
        }

        /** When the song changes, the outgoing episode's last position is still known here. */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val old = oldPosition.mediaItem ?: return
            if (old.mediaId == newPosition.mediaItem?.mediaId) return
            val track = old.toTrack()
            if (track.kind != Kind.EPISODE || oldPosition.positionMs < 5_000) return
            graph.scope.launch { graph.library.saveProgress(track, oldPosition.positionMs, track.durationSec * 1000L) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            crossfader.playWhenReadyChanged(playWhenReady)
            // The sleep timer's "end of song" pause has happened.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && graph.sleepTimer.sleep.value != null) {
                graph.sleepTimer.set(null)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSongs()

        override fun onRepeatModeChanged(repeatMode: Int) = saveSongs()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveFailures = 0
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                extendQueueIfLow()
                saveSongs()
            }
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
        if (!graph.settings.autoplay.value) return
        if (episodePlaying != null) return // songs "like" a podcast episode are not what anyone wants next
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
            val fresh = related
                .filter { it.videoId !in queued && it.videoId !in heard && !graph.library.isHidden(it) }
                .take(AUTOPLAY_BATCH)
            if (fresh.isNotEmpty()) player.addMediaItems(fresh.map { it.toMediaItem() })
        }
    }

    // ---------- sleep timer ----------

    private fun applySleep(sleep: Sleep?) {
        sleepJob?.cancel()
        sleepJob = null
        sound.setSleepFactor(1f) // undo a fade that was cut short
        player.pauseAtEndOfMediaItems = sleep is Sleep.EndOfSong
        if (sleep !is Sleep.At) return
        sleepJob = scope.launch {
            // Short sleeps so a phone that dozed while paused still stops on time.
            while (sleep.remainingMs() > SleepTimer.FADE_MS) delay(minOf(sleep.remainingMs() - SleepTimer.FADE_MS, 30_000L))
            while (true) {
                val left = sleep.remainingMs()
                if (left <= 0) break
                sound.setSleepFactor(left.toFloat() / SleepTimer.FADE_MS)
                delay(FADE_STEP_MS)
            }
            player.pause()
            sound.setSleepFactor(1f)
            graph.sleepTimer.set(null)
        }
    }

    /** "After N songs": each song that ends or is skipped counts one. */
    private fun countSongForSleep(reason: Int) {
        val sleep = graph.sleepTimer.sleep.value as? Sleep.AfterSongs ?: return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        graph.sleepTimer.set(if (sleep.songs - 1 <= 1) Sleep.EndOfSong else Sleep.AfterSongs(sleep.songs - 1))
    }

    /** Looks up the next song's stream address now, so the change of song has no gap. */
    private fun prefetchNext() {
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it) } ?: return
        val hint = next.localConfiguration?.uri?.let(::songHint)
        scope.launch(Dispatchers.IO) { runCatching { graph.streamUrls.prefetch(next.mediaId, hint) } }
    }

    // ---------- remembering the queue ----------

    private fun restoreQueue() {
        val saved = graph.queueStore.load() ?: return
        player.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        heard += saved.tracks.take(saved.index + 1).map { it.videoId }
        // Listeners are added after this, so tell the listening log which song is waiting.
        player.currentMediaItem?.let {
            val track = it.toTrack()
            graph.listenLog.songStarted(track, isPlaying = false)
            sound.levelFor(it.mediaId)
            episodePlaying = track.takeIf { t -> t.kind == Kind.EPISODE }
            sound.playingEpisode(track.kind == Kind.EPISODE)
        }
    }

    private var songsJob: Job? = null

    /**
     * The queue changed. Changes come in bursts (a drag, autoplay adding songs, a whole
     * playlist queued), so the file is written once, when they settle.
     */
    private fun saveSongs() {
        songsJob?.cancel()
        songsJob = scope.launch {
            delay(QUEUE_SAVE_DELAY_MS)
            writeSongs()
        }
    }

    private fun writeSongs() {
        val count = player.mediaItemCount
        if (count == 0) {
            graph.scope.launch(disk) { graph.queueStore.clear() }
            return
        }
        val saved = SavedQueue(
            tracks = List(count) { player.getMediaItemAt(it).toTrack() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        // onDestroy cancels the service's scope, so this write must not depend on it.
        graph.scope.launch(disk) { graph.queueStore.saveSongs(saved) }
        savePlace() // the place may have moved without a song change (a song above it removed or moved)
    }

    /** Picks an episode up where the listener left it. */
    private fun resumeEpisode(track: com.aurawave.innertube.Track) {
        scope.launch {
            val saved = graph.library.progressOf(track.videoId) ?: return@launch
            if (saved.played || saved.positionMs < 10_000 || saved.positionMs > saved.durationMs - 30_000) return@launch
            if (player.currentMediaItem?.mediaId == track.videoId && player.currentPosition < 5_000) player.seekTo(saved.positionMs)
        }
    }

    private fun saveEpisodeProgress() {
        val track = episodePlaying ?: return
        if (player.currentMediaItem?.mediaId != track.videoId) return
        val position = player.currentPosition
        if (position < 5_000) return // just started (or not yet moved to the saved place): nothing worth keeping
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track.durationSec * 1000L)
        graph.scope.launch { graph.library.saveProgress(track, position, duration) }
    }

    private fun savePlace() {
        saveEpisodeProgress()
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        // onDestroy cancels the scope, so this write must not depend on it.
        graph.scope.launch(disk) { graph.queueStore.savePlace(index, position) }
    }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 3
        const val AUTOPLAY_BATCH = 15
        const val PLACE_SAVE_INTERVAL_MS = 10_000L
        const val QUEUE_SAVE_DELAY_MS = 800L
        const val FADE_STEP_MS = 200L
    }
}
