package com.aurawave.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * A second place to find a song: JioSaavn's catalogue, which is strong on Indian music.
 * It is used only when YouTube cannot play a song, to find the same recording there.
 *
 * This talks to the interface JioSaavn's own website uses. It is not a published API, so
 * it can change or stop without notice; every failure here simply means "not found".
 */
class JioSaavn(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A song as JioSaavn lists it. */
    internal class Listing(
        val title: String,
        val artists: String,
        val album: String,
        val durationSec: Int,
        val encryptedUrl: String,
        val has320: Boolean,
    )

    /**
     * The same recording on JioSaavn as a playable stream, or null when it is not there or
     * cannot be told apart from other versions with confidence. Blocking.
     */
    @Throws(IOException::class)
    fun find(videoId: String, title: String, artist: String, durationSec: Int, quality: AudioQuality): AudioSource? {
        if (title.isBlank()) return null
        val lead = Match.splitArtists(artist).firstOrNull().orEmpty()
        val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", "search.getResults")
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "web6dot0")
            .addQueryParameter("n", "10")
            .addQueryParameter("p", "1")
            .addQueryParameter("q", "${Match.coreTitle(title)} $lead".trim())
            .build()
        val text = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { res ->
            if (!res.isSuccessful) return null
            res.body.string()
        }
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val best = pickRecording(parse(root), title, artist, durationSec) ?: return null
        val stream = decode(best.encryptedUrl)?.let { streamUrl(it, quality, best.has320) } ?: return null
        return AudioSource(
            videoId = videoId,
            url = stream.first,
            mimeType = "audio/mp4",
            codec = "mp4a",
            bitrateKbps = stream.second,
            contentLength = -1L,
            // These addresses carry no expiry; ask again after a while all the same.
            expiresAtMs = System.currentTimeMillis() + 6 * 3_600_000L,
        )
    }

    internal fun parse(root: JsonElement?): List<Listing> = root.at("results").arr.mapNotNull { r ->
        if (r.at("type").str != "song") return@mapNotNull null
        val more = r.at("more_info")
        val encrypted = more.at("encrypted_media_url").str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val artists = more.at("artistMap", "primary_artists").arr.mapNotNull { it.at("name").str }
            .ifEmpty { more.at("artistMap", "artists").arr.mapNotNull { it.at("name").str } }
        Listing(
            title = plain(r.at("title").str.orEmpty()),
            artists = artists.joinToString(", ") { plain(it) },
            album = plain(more.at("album").str.orEmpty()),
            durationSec = more.at("duration").str?.toIntOrNull() ?: 0,
            encryptedUrl = encrypted,
            has320 = more.at("320kbps").str == "true",
        )
    }

    /** The listing that is the recording asked for, by the same strict rules used for imports; null rather than a guess. */
    internal fun pickRecording(listings: List<Listing>, title: String, artist: String, durationSec: Int): Listing? {
        val want = Match.Want(title, artist, durationSec)
        return listings
            .map { it to Match.scoreCandidate(want, Match.Candidate(it.title, it.artists, it.album, it.durationSec, Kind.SONG, channel = it.artists)) }
            .filter { it.second.accept }
            .maxByOrNull { it.second.score }
            ?.first
    }

    /** JioSaavn lists stream addresses scrambled with a fixed key, the same for everyone. */
    internal fun decode(encrypted: String): String? = runCatching {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(), "DES"))
        String(cipher.doFinal(Base64.getDecoder().decode(encrypted.trim())))
    }.getOrNull()?.takeIf { it.startsWith("http") }

    /** The address for the wanted quality, and its bitrate. The listed address is the 96 kbit/s file. */
    internal fun streamUrl(listed: String, quality: AudioQuality, has320: Boolean): Pair<String, Int> {
        val kbps = when {
            quality == AudioQuality.LOW -> 96
            quality == AudioQuality.HIGH && has320 -> 320
            else -> 160
        }
        val secure = listed.replace("http://", "https://")
        return if ("_96." in secure) secure.replace("_96.", "_$kbps.") to kbps else secure to 96
    }

    private fun plain(text: String): String = text
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .trim()

    private companion object {
        const val KEY = "38346591"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
