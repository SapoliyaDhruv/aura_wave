package com.aurawave.player.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.aurawave.innertube.AudioQuality
import com.aurawave.innertube.AudioSource
import com.aurawave.innertube.StreamResolver
import com.aurawave.innertube.Track
import com.aurawave.player.AuraApp
import com.aurawave.player.MainActivity
import com.aurawave.player.R
import com.aurawave.player.data.Library
import com.aurawave.player.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One song's download. */
data class SongDownload(
    val track: Track,
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val updatedAt: Long,
) {
    val done: Boolean get() = state == Download.STATE_COMPLETED
    val active: Boolean get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val failed: Boolean get() = state == Download.STATE_FAILED
}

/**
 * Songs saved on the phone for offline play. They live in their own folder that is never
 * cleared to make room (unlike the playback cache), and play from there with no data
 * used. Downloads run in a foreground service, wait for Wi-Fi when the listener asks
 * for that, and stop at the listener's storage limit.
 */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    database: DatabaseProvider,
    http: OkHttpClient,
    private val resolver: StreamResolver,
    private val settings: Settings,
    private val library: Library,
    private val scope: CoroutineScope,
) {
    val cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database)

    private val json = Json { ignoreUnknownKeys = true }
    private val urls = ConcurrentHashMap<String, AudioSource>()

    val manager: DownloadManager = DownloadManager(
        context,
        database,
        cache,
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(rangedHttp(http))) { spec ->
            if (spec.uri.scheme != TRACK_SCHEME) spec else spec.withUri(Uri.parse(urlFor(spec.uri.lastPathSegment.orEmpty())))
        },
        Executors.newFixedThreadPool(2),
    ).apply {
        maxParallelDownloads = 2
        minRetryCount = 5
        requirements = requirementsFor(settings.downloadWifiOnly.value)
    }

    private val state = MutableStateFlow<Map<String, SongDownload>>(emptyMap())
    val songs: StateFlow<Map<String, SongDownload>> = state.asStateFlow()

    private val used = MutableStateFlow(0L)
    /** Bytes the downloads take on the phone. */
    val bytesUsed: StateFlow<Long> = used.asStateFlow()

    private var ticker: Job? = null

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
        })
        refresh()
        scope.launch {
            // The manager starts with the saved rule (above); only later changes need passing on.
            settings.downloadWifiOnly.flow.drop(1).collect { wifiOnly ->
                runCatching {
                    DownloadService.sendSetRequirements(context, AuraDownloadService::class.java, requirementsFor(wifiOnly), false)
                }
            }
        }
        // Liked songs follow the listener onto the phone when they ask for that.
        scope.launch {
            combine(settings.autoDownloadLiked.flow, library.liked) { on, liked -> if (on) liked else emptyList() }
                .collect { liked ->
                    if (liked.isEmpty()) return@collect
                    delay(2_000) // let the download list load first
                    val missing = liked.filter { it.videoId !in state.value }
                    if (missing.isNotEmpty()) download(missing, quiet = true)
                }
        }
    }

    /**
     * Queues songs for download. Returns a message for the listener when nothing could be
     * queued (the storage limit is reached), otherwise null.
     */
    fun download(tracks: List<Track>, quiet: Boolean = false): String? {
        val limit = settings.downloadLimitMb.value * MB
        if (limit > 0 && used.value >= limit) {
            return "Downloads have reached your limit of ${formatSize(limit)}. Raise it in Settings or remove some downloads."
        }
        val fresh = tracks.distinctBy { it.videoId }.filter { t -> state.value[t.videoId]?.let { it.failed } ?: true }
        for (t in fresh) {
            val request = DownloadRequest.Builder(t.videoId, trackUri(t.videoId))
                .setCustomCacheKey(downloadKey(t.videoId))
                .setData(json.encodeToString(Track.serializer(), t).toByteArray())
                .build()
            if (quiet) {
                // Queued from the background (auto-download of likes): Android may refuse to start a
                // service then, so the request goes straight to the manager, on its thread.
                scope.launch(Dispatchers.Main) {
                    manager.addDownload(request)
                    runCatching { DownloadService.start(context, AuraDownloadService::class.java) }
                }
            } else {
                runCatching { DownloadService.sendAddDownload(context, AuraDownloadService::class.java, request, true) }
                    .onFailure { scope.launch(Dispatchers.Main) { manager.addDownload(request) } }
            }
        }
        return null
    }

    fun remove(videoId: String) {
        runCatching { DownloadService.sendRemoveDownload(context, AuraDownloadService::class.java, videoId, false) }
    }

    fun removeAll() {
        runCatching { DownloadService.sendRemoveAllDownloads(context, AuraDownloadService::class.java, false) }
    }

    /** True when the whole song is on the phone. */
    fun isDownloaded(videoId: String): Boolean = state.value[videoId]?.done == true

    private fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    manager.downloadIndex.getDownloads().use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.download)
                        }
                    }
                }.getOrDefault(emptyList())
            }
            state.value = list.mapNotNull { d ->
                val track = runCatching { json.decodeFromString(Track.serializer(), String(d.request.data)) }.getOrNull() ?: return@mapNotNull null
                track.videoId to SongDownload(track, d.state, d.percentDownloaded.coerceAtLeast(0f), d.bytesDownloaded, d.updateTimeMs)
            }.toMap()
            used.value = withContext(Dispatchers.IO) { runCatching { cache.cacheSpace }.getOrDefault(0L) }
            enforceLimit()
            // Progress is not announced while a download runs, so look again every second until all are done.
            if (state.value.values.any { it.active } && ticker?.isActive != true) {
                ticker = scope.launch {
                    while (true) {
                        delay(1_000)
                        val current = manager.currentDownloads
                        if (current.isEmpty()) break
                        state.value = state.value + current.mapNotNull { d ->
                            state.value[d.request.id]?.let { d.request.id to it.copy(state = d.state, percent = d.percentDownloaded.coerceAtLeast(0f), bytes = d.bytesDownloaded) }
                        }
                    }
                    refresh()
                }
            }
        }
    }

    /** Over the limit (it was lowered, or a download finished past it): stop what is still queued. */
    private fun enforceLimit() {
        val limit = settings.downloadLimitMb.value * MB
        if (limit <= 0 || used.value < limit) return
        state.value.values.filter { it.active }.forEach { remove(it.track.videoId) }
    }

    /** Download URLs are looked up at the best quality, separately from what is streaming. */
    private fun urlFor(videoId: String): String {
        urls[videoId]?.takeIf { it.isFresh(marginMs = 30 * 60_000L) }?.let { return it.url }
        val source = resolver.resolve(videoId, AudioQuality.valueOf(settings.downloadQuality.value.ifEmpty { "HIGH" }))
        urls[videoId] = source
        return source.url
    }

    companion object {
        private const val MB = 1024L * 1024

        fun downloadKey(videoId: String) = "dl/$videoId"

        fun requirementsFor(wifiOnly: Boolean) = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * MB -> "%.1f GB".format(bytes / (1024.0 * MB))
            else -> "${bytes / MB} MB"
        }
    }
}

/** Runs downloads in the foreground, with a progress notification. */
@OptIn(UnstableApi::class)
class AuraDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as AuraApp).graph.downloads.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val message = when {
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> "Waiting for Wi-Fi"
            notMetRequirements and Requirements.NETWORK != 0 -> "Waiting for a connection"
            else -> "Downloading ${downloads.size} ${if (downloads.size == 1) "song" else "songs"}"
        }
        return notifications.buildProgressNotification(this, R.drawable.ic_stat_aurawave, open, message, downloads, notMetRequirements)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val JOB_ID = 1
        const val CHANNEL_ID = "downloads"
    }
}
