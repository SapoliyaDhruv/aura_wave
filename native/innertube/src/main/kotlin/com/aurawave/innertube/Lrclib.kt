package com.aurawave.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.min

data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics for one song: timed [lines] when a synced version exists, otherwise [plain] text. */
data class SongLyrics(val lines: List<LyricLine> = emptyList(), val plain: String = "", val source: String) {
    val isSynced: Boolean get() = lines.isNotEmpty()
}

/**
 * Synced lyrics from LRCLIB (lrclib.net), a free community database.
 * Ported from the web app's services/lyrics.js: the same lookups and the same
 * checks that the lyrics found belong to this recording.
 */
class Lrclib(private val http: OkHttpClient) {

    suspend fun find(track: Track): SongLyrics? = withContext(Dispatchers.IO) {
        val title = Match.coreTitle(track.title)
        val artist = track.artist.split(PRIMARY_ARTIST_SPLIT).firstOrNull().orEmpty()
        val candidates = mutableListOf<JsonObject>()

        if (track.durationSec > 0) {
            val exact = get("get", "track_name" to title, "artist_name" to artist, "duration" to track.durationSec.toString()) as? JsonObject
            val synced = exact.at("syncedLyrics").str
            if (!synced.isNullOrBlank()) return@withContext SongLyrics(lines = parseLrc(synced), source = SOURCE)
            if (exact != null) candidates += exact
        }

        candidates += get("search", "track_name" to title, "artist_name" to artist).arr.filterIsInstance<JsonObject>()
        if (candidates.isEmpty()) {
            candidates += get("search", "q" to "$title $artist").arr.filterIsInstance<JsonObject>()
        }

        val best = candidates
            .filter { c ->
                c.at("instrumental").bool != true &&
                    (!c.at("syncedLyrics").str.isNullOrBlank() || !c.at("plainLyrics").str.isNullOrBlank()) &&
                    isSameSong(track, c.at("trackName").str, c.at("artistName").str)
            }
            .filter { c ->
                val d = c.at("duration").num
                track.durationSec == 0 || d == null || d == 0.0 || abs(d - track.durationSec) < 20
            }
            .maxByOrNull { c ->
                val d = c.at("duration").num
                val penalty = if (track.durationSec > 0 && d != null && d != 0.0) min(60.0, abs(d - track.durationSec)) else 10.0
                (if (!c.at("syncedLyrics").str.isNullOrBlank()) 100.0 else 0.0) - penalty
            }
            ?: return@withContext null

        val synced = best.at("syncedLyrics").str
        if (!synced.isNullOrBlank()) SongLyrics(lines = parseLrc(synced), source = SOURCE)
        else SongLyrics(plain = best.at("plainLyrics").str.orEmpty(), source = SOURCE)
    }

    private fun isSameSong(track: Track, title: String?, artist: String?): Boolean =
        Match.titleSimilarity(track.title, title) >= 0.85 &&
            (artist.isNullOrEmpty() || Match.artistOverlap(track.artist, artist).any || Match.artistOverlap(artist, track.artist).any)

    /** One GET to LRCLIB. Any failure reads as "nothing found": lyrics are never worth an error. */
    private fun get(path: String, vararg params: Pair<String, String>): JsonElement? = try {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (res.isSuccessful) Json.parseToJsonElement(res.body.string()) else null
        }
    } catch (_: Exception) {
        null
    }

    private val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
    private val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val BASE = "https://lrclib.net/api"
        private const val SOURCE = "LRCLIB"
        private val PRIMARY_ARTIST_SPLIT = Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        private val STAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        private val ANY_TAG = Regex("""\[[^\]]*]""")

        /** Parses LRC text ("[01:02.30] words") into timed lines, in order. */
        fun parseLrc(lrc: String): List<LyricLine> {
            // LRC convention: a positive offset shows the lyrics sooner.
            val offsetMs = OFFSET.find(lrc)?.groupValues?.get(1)?.toLongOrNull()?.let { -it } ?: 0L
            val lines = mutableListOf<LyricLine>()
            for (raw in lrc.split('\n')) {
                val stamps = STAMP.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val text = raw.replace(ANY_TAG, "").trim()
                for (m in stamps) {
                    val (minutes, seconds, fraction) = m.destructured
                    val ms = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
                    val time = minutes.toLong() * 60_000 + seconds.toLong() * 1000 + ms + offsetMs
                    lines += LyricLine(maxOf(0L, time), text)
                }
            }
            lines.sortBy { it.timeMs }
            // Collapse runs of empty lines (instrumental gaps) into a single pause marker.
            return lines.filterIndexed { i, l -> l.text.isNotEmpty() || (i > 0 && lines[i - 1].text.isNotEmpty()) }
        }
    }
}
