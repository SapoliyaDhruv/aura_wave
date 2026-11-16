package com.aurawave.innertube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/** A direct, audio-only stream for one recording. The URL is temporary and tied to this device's IP. */
data class AudioSource(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val codec: String,
    val bitrateKbps: Int,
    val contentLength: Long,
    val expiresAtMs: Long,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis(), marginMs: Long = 5 * 60_000L): Boolean =
        expiresAtMs - nowMs > marginMs
}

/**
 * The song cannot be streamed, and trying again right away will not help: it was removed,
 * is private, region-blocked or age-gated, or its stream could not be read.
 * A plain IOException, by contrast, means YouTube could not be reached.
 */
class UnplayableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** How much data the listener is willing to spend. */
enum class AudioQuality(val maxKbps: Int) { LOW(70), NORMAL(140), HIGH(Int.MAX_VALUE) }

/**
 * Turns a video id into a playable audio-only stream URL, the way native players
 * (NewPipe, Namida) do: YouTube stores audio and video separately, so nothing but
 * audio is ever downloaded.
 *
 * The heavy lifting (signature and throttling-parameter deciphering) is done by
 * NewPipeExtractor, which the NewPipe project keeps in step with YouTube's changes.
 * When playback breaks after a YouTube change, updating that one dependency is the fix.
 */
class StreamResolver(http: OkHttpClient, region: String = "IN") {

    init {
        NewPipe.init(OkHttpDownloader(http), Localization("en", "US"), ContentCountry(region))
    }

    /** Blocking: call from a background thread. */
    @Throws(IOException::class)
    fun resolve(videoId: String, quality: AudioQuality = AudioQuality.HIGH): AudioSource {
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: ContentNotAvailableException) {
            throw UnplayableException(e.message ?: "This song is not available.", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Not a connection problem: YouTube answered, but the stream could not be read
            // (usually YouTube changed something). The player skips the song instead of waiting.
            throw UnplayableException("Could not read the stream for this song: ${e.message}", e)
        }

        val progressive = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // Auto-dubbed and audio-description tracks are never what a music listener wants.
        val original = progressive.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val candidates = original.ifEmpty { progressive }
        val best = pick(candidates, quality)
            ?: throw UnplayableException("No audio stream is available for this song.")

        val url = best.content
        return AudioSource(
            videoId = videoId,
            url = url,
            mimeType = best.format?.mimeType ?: "audio/*",
            codec = best.codec.orEmpty(),
            bitrateKbps = kbps(best),
            contentLength = best.itagItem?.contentLength ?: -1L,
            expiresAtMs = expiryOf(url),
        )
    }

    /** Video-only streams for the same recording, best first (for the audio/video switch). */
    @Throws(IOException::class)
    fun videoUrls(videoId: String, maxHeight: Int = 720): List<Pair<Int, String>> {
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not read the video for $videoId: ${e.message}", e)
        }
        return info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..maxHeight }
            .sortedByDescending { it.height }
            .map { it.height to it.content }
    }

    private fun kbps(s: AudioStream): Int = when {
        s.averageBitrate > 0 -> s.averageBitrate
        s.bitrate > 0 -> s.bitrate / 1000
        else -> 0
    }

    private fun isOpus(s: AudioStream): Boolean = s.codec?.contains("opus", ignoreCase = true) == true

    /**
     * Opus sounds better than AAC at the same bitrate, so it wins within the data budget.
     * If nothing fits the budget, the smallest stream is used rather than failing.
     */
    private fun pick(streams: List<AudioStream>, quality: AudioQuality): AudioStream? {
        if (streams.isEmpty()) return null
        val within = streams.filter { kbps(it) <= quality.maxKbps }
        if (within.isEmpty()) return streams.minByOrNull { kbps(it) }
        return within.maxWithOrNull(compareBy<AudioStream> { kbps(it) + if (isOpus(it)) 40 else 0 })
    }

    private fun expiryOf(url: String): Long {
        val seconds = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()
        // No expiry in the URL: assume a conservative hour.
        return if (seconds != null) seconds * 1000 else System.currentTimeMillis() + 3600_000L
    }

    private companion object {
        val EXPIRE = Regex("""[?&/]expire[=/](\d+)""")
    }
}

/** Lets NewPipeExtractor make its requests through the app's shared OkHttp client. */
private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val method = request.httpMethod()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder().url(request.url()).method(method, body)
        builder.header("User-Agent", USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        http.newCall(builder.build()).execute().use { res ->
            if (res.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
            return Response(res.code, res.message, res.headers.toMultimap(), res.body.string(), res.request.url.toString())
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
