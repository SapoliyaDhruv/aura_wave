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
import com.aurawave.innertube.AudioSource
import com.aurawave.innertube.StreamResolver
import com.aurawave.innertube.UnplayableException
import com.aurawave.player.AppGraph
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Stream URLs by video id. A URL is reused until shortly before it expires; a song
 * that cannot be streamed is remembered briefly so retries don't hammer YouTube.
 */
class StreamUrlCache(private val resolver: StreamResolver) {
    private class Failure(val error: UnplayableException, val at: Long)

    private val sources = ConcurrentHashMap<String, AudioSource>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Blocking. Throws [UnplayableException] when the song cannot be streamed, or IOException when YouTube cannot be reached. */
    @Throws(IOException::class)
    fun get(videoId: String): AudioSource {
        sources[videoId]?.takeIf { it.isFresh() }?.let { return it }
        // One lookup per song at a time: the player and its look-ahead may ask together.
        synchronized(locks.getOrPut(videoId) { Any() }) {
            sources[videoId]?.takeIf { it.isFresh() }?.let { return it }
            failures[videoId]?.takeIf { System.currentTimeMillis() - it.at < FAILURE_MEMORY_MS }?.let { throw it.error }
            try {
                val source = resolver.resolve(videoId)
                sources[videoId] = source
                failures.remove(videoId)
                return source
            } catch (e: UnplayableException) {
                failures[videoId] = Failure(e, System.currentTimeMillis())
                throw e
            }
        }
    }

    /** Forget a URL that YouTube refused, so the next attempt asks for a new one. */
    fun invalidate(videoId: String) {
        sources.remove(videoId)
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
    private val urls: StreamUrlCache,
    private val cache: Cache,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != TRACK_SCHEME) return upstream.open(dataSpec)
        val videoId = uri.lastPathSegment ?: throw IOException("Song address has no id: $uri")
        if (isCachedToEnd(dataSpec.key ?: videoId, dataSpec)) return upstream.open(dataSpec)

        val source = try {
            urls.get(videoId)
        } catch (e: UnplayableException) {
            throw e
        } catch (e: IOException) {
            // Tell the player this is a connection problem, not a broken song.
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        try {
            return upstream.open(dataSpec.withUri(Uri.parse(source.url)))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403 || e.responseCode == 410) urls.invalidate(videoId)
            throw e
        }
    }

    private fun isCachedToEnd(key: String, spec: DataSpec): Boolean {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (total == C.LENGTH_UNSET.toLong()) return false
        val remaining = total - spec.position
        return remaining <= 0 || cache.isCached(key, spec.position, remaining)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

@UnstableApi
fun streamDataSourceFactory(graph: AppGraph): DataSource.Factory {
    // YouTube throttles a request that has no Range header to about twice playback speed
    // (measured: ~35 KB/s without, ~1 MB/s with; see LiveThroughputTest). The player sends no
    // Range when it starts a song from the beginning, so one is added here.
    val ranged = graph.http.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("Range") == null) request.newBuilder().header("Range", "bytes=0-").build() else request,
            )
        }
        .build()
    val network = OkHttpDataSource.Factory(ranged)
    val cached = CacheDataSource.Factory()
        .setCache(graph.mediaCache)
        .setUpstreamDataSourceFactory(network)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return DataSource.Factory { StreamDataSource(cached.createDataSource(), graph.streamUrls, graph.mediaCache) }
}

/** Retrying cannot fix a song YouTube will not stream, so give up on it immediately. */
@UnstableApi
class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.isUnplayable()) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}

fun Throwable.isUnplayable(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is UnplayableException }
