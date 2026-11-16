package com.aurawave.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * A second way to get a song's audio stream from YouTube, for when [StreamResolver]
 * (NewPipeExtractor) cannot. It asks YouTube's player service directly, as some of
 * YouTube's own apps do, and gets stream addresses that need no deciphering.
 *
 * YouTube often hands such addresses out and then refuses them after the first megabyte.
 * So every address is tried a good way into the song before it is accepted: this either
 * returns a stream that plays to the end, or declines.
 */
class BackupStreams(private val http: OkHttpClient, private val region: () -> String = { "IN" }) {
    private val json = Json { ignoreUnknownKeys = true }

    private class Client(val name: String, val version: String, val number: String, val userAgent: String, val device: Map<String, String> = emptyMap(), val sdk: Int = 0)

    private class Format(val url: String, val mimeType: String, val kbps: Int, val length: Long)

    /** Blocking. Throws [UnplayableException] when no client gives a stream that plays through. */
    @Throws(IOException::class)
    fun resolve(videoId: String, quality: AudioQuality = AudioQuality.HIGH): AudioSource {
        var why = "YouTube gave no stream"
        for (client in CLIENTS) {
            val (formats, expiresAt, status) = ask(client, videoId)
            if (formats.isEmpty()) {
                why = status
                continue
            }
            val best = pick(formats, quality)
            if (!playsThrough(best)) {
                why = "YouTube refused the stream part-way"
                continue
            }
            return AudioSource(
                videoId = videoId,
                url = best.url,
                mimeType = best.mimeType.substringBefore(';'),
                codec = if ("opus" in best.mimeType) "opus" else "mp4a",
                bitrateKbps = best.kbps,
                contentLength = best.length,
                expiresAtMs = expiresAt,
            )
        }
        throw UnplayableException("The backup route found no playable stream: $why")
    }

    private fun ask(client: Client, videoId: String): Triple<List<Format>, Long, String> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("userAgent", client.userAgent)
                    put("hl", "en")
                    put("gl", region())
                    client.device.forEach { (k, v) -> put(k, v) }
                    if (client.sdk > 0) put("androidSdkVersion", client.sdk)
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.number)
            .header("X-YouTube-Client-Version", client.version)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val text = http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return Triple(emptyList(), 0L, "YouTube answered ${res.code}")
            res.body.string()
        }
        val root: JsonElement = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Triple(emptyList(), 0L, "YouTube's answer could not be read")
        val status = root.at("playabilityStatus", "status").str
        if (status != "OK") {
            return Triple(emptyList(), 0L, root.at("playabilityStatus", "reason").str ?: "YouTube said $status")
        }
        val streaming = root.at("streamingData")
        val formats = streaming.at("adaptiveFormats").arr.mapNotNull { f ->
            val mime = f.at("mimeType").str ?: return@mapNotNull null
            val url = f.at("url").str ?: return@mapNotNull null // a ciphered address would need the deciphering this route avoids
            if (!mime.startsWith("audio/")) return@mapNotNull null
            // Auto-dubbed tracks are never what a music listener wants.
            if (f.at("audioTrack") != null && f.at("audioTrack", "audioIsDefault").str != "true") return@mapNotNull null
            val bits = f.at("averageBitrate").str?.toIntOrNull() ?: f.at("bitrate").str?.toIntOrNull() ?: 0
            Format(url, mime, bits / 1000, f.at("contentLength").str?.toLongOrNull() ?: -1L)
        }
        val lifetime = streaming.at("expiresInSeconds").str?.toLongOrNull() ?: 3600L
        return Triple(formats, System.currentTimeMillis() + lifetime * 1000, "YouTube listed no audio")
    }

    /** Opus first within the data budget, as in [StreamResolver]; the smallest stream when nothing fits. */
    private fun pick(formats: List<Format>, quality: AudioQuality): Format {
        val within = formats.filter { it.kbps <= quality.maxKbps }
        if (within.isEmpty()) return formats.minBy { it.kbps }
        return within.maxBy { it.kbps + if ("opus" in it.mimeType) 40 else 0 }
    }

    /** Reads a few bytes from well past the first megabyte, where YouTube refuses streams it will not serve in full. */
    private fun playsThrough(format: Format): Boolean {
        val from = if (format.length > 0) format.length * 3 / 5 else PROBE_OFFSET
        val request = Request.Builder().url(format.url).header("Range", "bytes=$from-${from + 1023}").build()
        return try {
            http.newCall(request).execute().use { it.code == 206 || it.code == 200 }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        const val PROBE_OFFSET = 2L * 1024 * 1024

        /** Tried in this order. Which of them YouTube serves in full changes over time, and by network. */
        val CLIENTS = listOf(
            Client(
                name = "ANDROID_VR", version = "1.65.10", number = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                device = mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L"),
                sdk = 32,
            ),
            Client(
                name = "IOS", version = "20.10.4", number = "5",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                device = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
            ),
            Client(
                name = "ANDROID", version = "20.10.38", number = "3",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                device = mapOf("osName" to "Android", "osVersion" to "11"),
                sdk = 30,
            ),
        )
    }
}
