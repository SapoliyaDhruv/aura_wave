package com.aurawave.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.aurawave.innertube.AudioQuality
import com.aurawave.innertube.AudioSource
import com.aurawave.innertube.BackupStreams
import com.aurawave.innertube.JioSaavn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.aurawave.innertube.StreamResolver
import com.aurawave.innertube.UnplayableException
import com.aurawave.player.AppGraph
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Stream URLs by video id. A URL is reused until shortly before it expires; a song
 * that cannot be streamed is remembered briefly so retries don't hammer YouTube.
 */
class StreamUrlCache(
    private val resolver: StreamResolver,
    /** A second way to get the stream from YouTube, tried when [resolver] cannot. */
    private val backup: BackupStreams,
    /** A second catalogue, tried last, when YouTube cannot play the song at all. */
    private val saavn: JioSaavn,
    private val useSaavn: () -> Boolean,
    private val qualityNow: () -> AudioQuality,
) {
    private class Failure(val error: UnplayableException, val at: Long)

    private val sources = ConcurrentHashMap<String, AudioSource>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * The quality each song is played at, fixed the first time it is opened: a song must keep
     * the same stream from start to end, even if the phone moves from Wi-Fi to mobile data.
     */
    private val chosen = ConcurrentHashMap<String, AudioQuality>()

    fun qualityFor(videoId: String): AudioQuality = chosen.getOrPut(videoId) { qualityNow() }

    /** The quality a song already started in, or null if it has not been opened yet this session. */
    fun startedIn(videoId: String): AudioQuality? = chosen[videoId]

    private val fromDownload = ConcurrentHashMap.newKeySet<String>()

    /** Songs playing from the downloads folder this session; they stay on that copy. */
    fun playsFromDownload(videoId: String): Boolean = videoId in fromDownload

    fun markFromDownload(videoId: String) {
        fromDownload += videoId
    }

    fun useQuality(videoId: String, quality: AudioQuality) {
        chosen[videoId] = quality
    }

    private val origins = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Songs playing from somewhere other than the usual route, by video id, and where from ("JioSaavn"). */
    val playingFrom: StateFlow<Map<String, String>> = origins.asStateFlow()

    /** Blocking. Throws [UnplayableException] when the song cannot be streamed, or IOException when YouTube cannot be reached. */
    @Throws(IOException::class)
    fun get(videoId: String, hint: SongHint? = null): AudioSource = lookUp(videoId, qualityFor(videoId), hint)

    /**
     * Looks up the next song's address ahead of time without deciding its quality or source:
     * that is decided when it is opened (it may be downloaded, or cached in another quality).
     */
    fun prefetch(videoId: String, hint: SongHint? = null) {
        lookUp(videoId, chosen[videoId] ?: qualityNow(), hint)
    }

    private fun lookUp(videoId: String, quality: AudioQuality, hint: SongHint?): AudioSource {
        val key = "$videoId/${quality.name}"
        sources[key]?.takeIf { it.isFresh() }?.let { return it }
        // One lookup per song at a time: the player and its look-ahead may ask together.
        synchronized(locks.getOrPut(videoId) { Any() }) {
            sources[key]?.takeIf { it.isFresh() }?.let { return it }
            failures[videoId]?.takeIf { System.currentTimeMillis() - it.at < FAILURE_MEMORY_MS }?.let { throw it.error }
            try {
                val source = try {
                    resolver.resolve(videoId, quality)
                } catch (e: UnplayableException) {
                    elsewhere(videoId, quality, hint) ?: throw e
                }
                sources[key] = source
                failures.remove(videoId)
                return source
            } catch (e: UnplayableException) {
                failures[videoId] = Failure(e, System.currentTimeMillis())
                throw e
            }
        }
    }

    /**
     * The usual route could not play the song. Try YouTube a second way; then, for a song
     * whose name is known, the same recording on JioSaavn. Null when neither has it.
     */
    private fun elsewhere(videoId: String, quality: AudioQuality, hint: SongHint?): AudioSource? {
        try {
            return backup.resolve(videoId, quality).also { note(videoId, null) }
        } catch (_: IOException) {
            // Declined, or unreachable: carry on to the next place.
        }
        if (hint == null || !useSaavn()) return null
        return try {
            saavn.find(videoId, hint.title, hint.artist, hint.durationSec, quality)?.also { note(videoId, "JioSaavn") }
        } catch (_: IOException) {
            null
        }
    }

    private fun note(videoId: String, origin: String?) {
        origins.update { if (origin == null) it - videoId else it + (videoId to origin) }
    }

    /** Forget a URL that YouTube refused, so the next attempt asks for a new one. */
    fun invalidate(videoId: String) {
        AudioQuality.entries.forEach { sources.remove("$videoId/${it.name}") }
    }

    private companion object {
        const val FAILURE_MEMORY_MS = 5 * 60_000L
    }
}

/**
 * Sits between the player and the network. When the player opens a song it:
 *  1. plays straight from the disk cache if the rest of the song is already there (works offline),
 *  2. otherwise looks up the audio-only stream URL and opens that,
 *  3. and if YouTube refuses the URL (expired, or issued on another network), forgets it,
 *     so the player's automatic retry gets a fresh one and continues from the same position.
 */
@UnstableApi
class StreamDataSource(
    private val upstream: DataSource,
    private val offline: DataSource,
    private val urls: StreamUrlCache,
    private val cache: Cache,
    private val downloads: Cache,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        offline.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        current = upstream
        if (uri.scheme != TRACK_SCHEME) return upstream.open(dataSpec)
        val videoId = uri.lastPathSegment ?: throw IOException("Song address has no id: $uri")

        // A downloaded song plays from the downloads folder, unless it already started from the stream.
        if (urls.startedIn(videoId) == null || urls.playsFromDownload(videoId)) {
            val key = Downloads.downloadKey(videoId)
            if (isCachedToEnd(downloads, key, dataSpec)) {
                urls.markFromDownload(videoId)
                current = offline
                return offline.open(dataSpec.buildUpon().setKey(key).build())
            }
        }

        // Each quality is cached separately. A song opened for the first time plays from any quality already
        // on the phone (offline too); once it has started, it stays in that quality, since the files differ.
        val started = urls.startedIn(videoId)
        val cached = (if (started != null) listOf(started) else AudioQuality.entries)
            .firstOrNull { isCachedToEnd(cache, cacheKey(videoId, it), dataSpec) }
        if (cached != null) {
            urls.useQuality(videoId, cached)
            return upstream.open(dataSpec.buildUpon().setKey(cacheKey(videoId, cached)).build())
        }
        val key = cacheKey(videoId, urls.qualityFor(videoId))

        val source = try {
            urls.get(videoId, songHint(uri))
        } catch (e: UnplayableException) {
            throw e
        } catch (e: IOException) {
            // Tell the player this is a connection problem, not a broken song.
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        try {
            return upstream.open(dataSpec.buildUpon().setUri(Uri.parse(source.url)).setKey(key).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403 || e.responseCode == 410) urls.invalidate(videoId)
            throw e
        }
    }

    private fun isCachedToEnd(cache: Cache, key: String, spec: DataSpec): Boolean {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (total == C.LENGTH_UNSET.toLong()) return false
        val remaining = total - spec.position
        return remaining <= 0 || cache.isCached(key, spec.position, remaining)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw IOException("Read before open")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

/**
 * YouTube throttles a request that has no Range header to about twice playback speed
 * (measured: ~35 KB/s without, ~1 MB/s with; see LiveThroughputTest). The player sends no
 * Range when it starts a song from the beginning, so one is added here.
 */
fun rangedHttp(http: OkHttpClient): OkHttpClient = http.newBuilder()
    .addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(
            if (request.header("Range") == null) request.newBuilder().header("Range", "bytes=0-").build() else request,
        )
    }
    .build()

@UnstableApi
fun streamDataSourceFactory(graph: AppGraph): DataSource.Factory {
    val network = OkHttpDataSource.Factory(rangedHttp(graph.http))
    val cached = CacheDataSource.Factory()
        .setCache(graph.mediaCache)
        .setUpstreamDataSourceFactory(network)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    // Downloads are read only from the phone: no network behind them, and nothing written.
    val downloaded = CacheDataSource.Factory()
        .setCache(graph.downloads.cache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
    return DataSource.Factory {
        StreamDataSource(cached.createDataSource(), downloaded.createDataSource(), graph.streamUrls, graph.mediaCache, graph.downloads.cache)
    }
}

/** Retrying cannot fix a song YouTube will not stream, so give up on it immediately. */
@UnstableApi
class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.isUnplayable()) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}

/** Where a song's audio is kept in the disk cache, per quality. */
fun cacheKey(videoId: String, quality: AudioQuality): String = "$videoId/${quality.name}"

fun Throwable.isUnplayable(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is UnplayableException }
