package com.aurawave.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * How loud a recording is, as YouTube measured it, so quiet and loud songs can be evened
 * out. YouTube's iOS client reports each track's loudness (LKFS) and the level YouTube
 * itself plays everything at (-14 LKFS).
 */
class Loudness(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decibels to add to this song to reach YouTube's level, or null when YouTube gave no figure. */
    suspend fun gainDb(videoId: String): Double? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "IOS")
                    put("clientVersion", IOS_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                    put("hl", "en")
                }
            }
            put("videoId", videoId)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", "com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = try {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else return@withContext null }
        } catch (_: IOException) {
            return@withContext null
        }
        val config = runCatching { json.parseToJsonElement(text) }.getOrNull().at("playerConfig", "audioConfig")
        val track = config.at("trackAbsoluteLoudnessLkfs").str?.toDoubleOrNull()
            ?: config.at("perceptualLoudnessDb").str?.toDoubleOrNull()
            ?: return@withContext null
        val target = config.at("loudnessTargetLkfs").str?.toDoubleOrNull() ?: DEFAULT_TARGET
        (target - track).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    companion object {
        private const val IOS_VERSION = "20.10.4"
        private const val DEFAULT_TARGET = -14.0
        /** Quiet songs are lifted by at most this much, so their noise floor does not come up with them. */
        const val MAX_GAIN = 6.0
        const val MIN_GAIN = -15.0
    }
}
