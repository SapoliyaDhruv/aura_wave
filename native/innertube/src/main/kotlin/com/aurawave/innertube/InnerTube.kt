package com.aurawave.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class InnerTubeException(message: String, val status: Int = 0) : IOException(message)

/**
 * YouTube Music client (the private "InnerTube" API the YouTube Music website uses).
 *
 * It runs on the phone, so there is no server of ours between the app and YouTube.
 * This class only reads the catalogue: search, radio, artists, lyrics. Audio streams
 * come from [StreamResolver].
 */
class InnerTube(
    private val http: OkHttpClient,
    private val region: () -> String = { "IN" },
) {
    @Volatile
    private var clientVersion = "1.20250915.03.00"
    private val versionRefreshed = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    // ---------- transport ----------

    /** An outdated client version is the usual cause of 400s: read the current one from the site, once. */
    private fun refreshClientVersion(): Boolean {
        if (!versionRefreshed.compareAndSet(false, true)) return false
        return try {
            val req = Request.Builder().url(ORIGIN).header("User-Agent", UA).build()
            val html = http.newCall(req).execute().use { it.body.string() }
            val found = CLIENT_VERSION.find(html)?.groupValues?.get(1) ?: return false
            clientVersion = found
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): Pair<Int, String> {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", clientVersion)
                    put("hl", "en")
                    put("gl", region())
                }
            }
            body()
        }
        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", UA)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        return http.newCall(req).execute().use { it.code to it.body.string() }
    }

    /** One retry for dropped connections, server errors and an outdated client version. */
    private suspend fun call(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val (status, text) = try {
                post(endpoint, body)
            } catch (e: IOException) {
                if (attempt++ == 0) continue
                throw e
            }
            if (status in 200..299) return@withContext json.parseToJsonElement(text).jsonObject
            val retry = attempt++ == 0 && (status >= 500 || ((status == 400 || status == 403) && refreshClientVersion()))
            if (!retry) throw InnerTubeException("YouTube Music $endpoint failed with $status", status)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    // ---------- parsing ----------

    private class Byline(
        val artists: MutableList<ArtistRef> = mutableListOf(),
        var album: String = "",
        var albumId: String = "",
        var duration: Int = 0,
        var year: String = "",
    )

    private fun runsText(t: JsonElement?): String {
        val joined = t.at("runs").arr.joinToString("") { it.at("text").str.orEmpty() }
        return joined.ifEmpty { t.at("simpleText").str.orEmpty() }
    }

    private fun parseDuration(s: String?): Int {
        if (s == null || !DURATION.matches(s)) return 0
        return s.split(':').fold(0) { acc, n -> acc * 60 + n.toInt() }
    }

    private fun bestThumb(thumbs: List<JsonElement>): String =
        bigCover(thumbs.lastOrNull().at("url").str)

    private fun pageType(run: JsonElement?): String? = run.at(
        "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType",
    ).str

    private fun browseId(run: JsonElement?): String =
        run.at("navigationEndpoint", "browseEndpoint", "browseId").str.orEmpty()

    /** Turns the subtitle runs ("Artist • Album • 4:22") into structured fields. */
    private fun parseByline(runs: List<JsonElement>): Byline {
        val out = Byline()
        val loose = mutableListOf<String>()
        for (r in runs) {
            val text = r.at("text").str?.trim()
            if (text.isNullOrEmpty() || text == "•" || text == "," || text == "&") continue
            val type = pageType(r)
            when {
                type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL" ->
                    out.artists += ArtistRef(text, browseId(r))
                type == "MUSIC_PAGE_TYPE_ALBUM" -> { out.album = text; out.albumId = browseId(r) }
                parseDuration(text) > 0 -> out.duration = parseDuration(text)
                YEAR.matches(text) -> out.year = text
                !AUDIENCE_SUFFIX.containsMatchIn(text) && text !in ITEM_TYPES -> loose += text
            }
        }
        // Some items list artist names as plain text (no link). Treat leading loose text as artists.
        if (out.artists.isEmpty() && loose.isNotEmpty()) {
            loose[0].split(NAME_SPLIT).filter { it.isNotEmpty() }.forEach { out.artists += ArtistRef(it) }
        }
        return out
    }

    private fun videoTypeOf(renderer: JsonElement?): String {
        val cfg = arrayOf("watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType")
        return renderer.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", *cfg).str
            ?: renderer.at("navigationEndpoint", *cfg).str
            ?: ""
    }

    private fun toTrack(videoId: String, title: String, byline: Byline, thumbs: List<JsonElement>, videoType: String, plays: String = ""): Track =
        Track(
            videoId = videoId,
            title = title,
            artist = byline.artists.joinToString(", ") { it.name }.ifEmpty { "Unknown artist" },
            artists = byline.artists.toList(),
            album = byline.album,
            albumId = byline.albumId,
            year = byline.year,
            durationSec = byline.duration,
            coverUrl = bestThumb(thumbs).ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            // song = official audio track, video = official music video, upload = fan/channel upload
            kind = when (videoType) {
                "MUSIC_VIDEO_TYPE_ATV" -> Kind.SONG
                "MUSIC_VIDEO_TYPE_OMV" -> Kind.VIDEO
                else -> Kind.UPLOAD
            },
            plays = plays,
        )

    /** Parses a musicResponsiveListItemRenderer (search results, artist top songs). */
    private fun parseListItem(r: JsonElement?): Track? {
        val videoId = r.at("playlistItemData", "videoId").str
            ?: r.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").str
            ?: return null
        val cols = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        val title = runsText(cols.firstOrNull())
        if (title.isEmpty()) return null
        val subtitleRuns = mutableListOf<JsonElement>()
        cols.drop(1).forEachIndexed { i, c ->
            if (i > 0) subtitleRuns += BULLET
            subtitleRuns += c.at("runs").arr
        }
        val byline = parseByline(subtitleRuns)
        if (byline.duration == 0) {
            byline.duration = r.at("fixedColumns").arr
                .map { parseDuration(runsText(it.at("musicResponsiveListItemFixedColumnRenderer", "text"))) }
                .firstOrNull { it > 0 } ?: 0
        }
        val plays = subtitleRuns.mapNotNull { it.at("text").str }.firstOrNull { PLAYS_SUFFIX.containsMatchIn(it) }.orEmpty()
        val thumbs = r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr
        return toTrack(videoId, title, byline, thumbs, videoTypeOf(r), plays)
    }

    /** Parses a playlistPanelVideoRenderer (radio / up next). */
    private fun parsePanelItem(r: JsonElement?): Track? {
        val videoId = r.at("videoId").str ?: return null
        val byline = parseByline(r.at("longBylineText", "runs").arr)
        if (byline.duration == 0) byline.duration = parseDuration(runsText(r.at("lengthText")))
        return toTrack(videoId, runsText(r.at("title")), byline, r.at("thumbnail", "thumbnails").arr, videoTypeOf(r))
    }

    private fun List<Track?>.uniqueById(): List<Track> {
        val seen = HashSet<String>()
        return filterNotNull().filter { seen.add(it.videoId) }
    }

    // ---------- public API ----------

    suspend fun searchSongs(query: String, filter: String = FILTER_SONGS): List<Track> {
        val data = call("search") { put("query", query); put("params", filter) }
        return data.collect("musicResponsiveListItemRenderer").map(::parseListItem).uniqueById()
    }

    suspend fun searchArtists(query: String, limit: Int = 4): List<ArtistSummary> {
        val data = call("search") { put("query", query); put("params", FILTER_ARTISTS) }
        return data.collect("musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = browseId(r)
            if (!id.startsWith("UC")) return@mapNotNull null
            val cols = r.at("flexColumns").arr.map { runsText(it.at("musicResponsiveListItemFlexColumnRenderer", "text")) }
            ArtistSummary(
                id = id,
                name = cols.getOrNull(0).orEmpty(),
                audience = cols.getOrNull(1).orEmpty().replace(ARTIST_PREFIX, ""),
                imageUrl = bigCover(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr.lastOrNull().at("url").str, 400),
            )
        }.take(limit)
    }

    /** Songs, artists and official videos for a free-text (or lyrics) query. */
    suspend fun search(query: String): SearchResult = coroutineScope {
        val songsJob = async { searchSongs(query, FILTER_SONGS) }
        val videosJob = async { runCatching { searchSongs(query, FILTER_VIDEOS) }.getOrDefault(emptyList()) }
        val artistsJob = async { runCatching { searchArtists(query, 3) }.getOrDefault(emptyList()) }
        val songs = songsJob.await()
        val videos = videosJob.await()
        val artists = artistsJob.await()

        // Official music videos only fill gaps (songs missing from the audio catalogue).
        val songKeys = songs.map { "${it.title.lowercase()}::${it.artist.lowercase()}" }.toSet()
        val extraVideos = videos
            .filter { it.kind == Kind.VIDEO && it.durationSec in 90..900 }
            .filter { Match.versionFlags(it.title).isEmpty() && !COMPILATION.containsMatchIn(it.title) }
            .filter { "${it.title.lowercase()}::${it.artist.lowercase()}" !in songKeys }
            .take(4)

        // Hide altered versions (instrumental, karaoke, slowed, 8D/jhankar, clips) unless the query
        // asks for them, and list remixes/lofi/covers/live after the originals.
        val asked = Match.versionFlags(query)
        val ranked = songs
            .filter { it.durationSec == 0 || it.durationSec < 1200 }
            .map { s -> s to (Match.versionFlags(s.title) + Match.versionFlags(s.album, album = true)).filter { it !in asked } }
            .filter { (_, flags) -> flags.none { it in Match.HIDDEN_VERSIONS } }
            .sortedBy { (_, flags) -> if (flags.isEmpty()) 0 else 1 } // stable: keeps YouTube's order within each group
            .map { it.first }

        SearchResult(songs = ranked, videos = extraVideos, artists = artists)
    }

    /** What YouTube Music suggests while the listener is still typing. */
    suspend fun suggestions(input: String): List<String> {
        val data = call("music/get_search_suggestions") { put("input", input) }
        return data.collect("searchSuggestionRenderer")
            .map { runsText(it.at("suggestion")) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Related tracks ("radio") seeded by a recording. The seed itself is excluded. */
    suspend fun radio(videoId: String): List<Track> {
        val data = call("next") {
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
        }
        return data.collect("playlistPanelVideoRenderer").map(::parsePanelItem).uniqueById()
            .filter { it.videoId != videoId }
            .filter { Match.versionFlags(it.title).isEmpty() }
    }

    suspend fun artist(id: String = "", name: String = ""): ArtistPage? {
        val artistId = id.ifEmpty { if (name.isNotEmpty()) searchArtists(name, 1).firstOrNull()?.id.orEmpty() else "" }
        if (artistId.isEmpty()) return null
        val page = call("browse") { put("browseId", artistId) }
        val header = page.at("header", "musicImmersiveHeaderRenderer") ?: page.at("header", "musicVisualHeaderRenderer")
        val artistName = runsText(header.at("title")).ifEmpty { name }
        val shelf = page.collect("musicShelfRenderer").firstOrNull()
        val top = shelf.at("contents").arr.mapNotNull { parseListItem(it.at("musicResponsiveListItemRenderer")) }
        // Widen with a song search, keeping only tracks credited to this artist.
        val more = runCatching { searchSongs(artistName) }.getOrDefault(emptyList())
        fun norm(s: String) = s.lowercase().replace(NOT_ASCII_ALNUM, "")
        val credited = more.filter { t -> t.artists.any { it.id == artistId || norm(it.name) == norm(artistName) } }
        val thumbs = header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr
        return ArtistPage(
            id = artistId,
            name = artistName,
            imageUrl = thumbs.lastOrNull().at("url").str.orEmpty(),
            audience = runsText(header.at("monthlyListenerCount")),
            songs = (top + credited).uniqueById().take(30),
        )
    }

    /** Plain lyrics from YouTube Music (fallback when no synced lyrics exist). */
    suspend fun lyrics(videoId: String): Lyrics? {
        val next = call("next") { put("videoId", videoId) }
        val browseId = LYRICS_ID.find(next.toString())?.groupValues?.get(1) ?: return null
        val page = call("browse") { put("browseId", browseId) }
        val shelf = page.collect("musicDescriptionShelfRenderer").firstOrNull()
        val text = runsText(shelf.at("description"))
        if (text.isEmpty()) return null
        return Lyrics(plain = text, source = runsText(shelf.at("footer")).replace(SOURCE_PREFIX, ""))
    }

    /**
     * Finds the exact recording for a song described by metadata (playlist imports,
     * old library entries). Returns no track rather than guessing: playing nothing
     * is better than playing the wrong song.
     */
    suspend fun resolve(title: String, artist: String = "", durationSec: Int = 0, exclude: Set<String> = emptySet()): Resolution {
        val want = Match.Want(title, artist, durationSec)
        val primaryArtist = artist.split(PRIMARY_ARTIST_SPLIT).firstOrNull().orEmpty()
        val query = "$title $primaryArtist".trim()

        fun rank(list: List<Track>) = list
            .filter { it.videoId !in exclude }
            .map { c -> c to Match.scoreCandidate(want, Match.Candidate(c.title, c.artist, c.album, c.durationSec, c.kind, channel = c.artist)) }
            .sortedByDescending { it.second.score }

        val songs = rank(searchSongs(query, FILTER_SONGS))
        songs.firstOrNull { it.second.accept }?.let { return Resolution(it.first, it.second.score, "song") }

        val videos = rank(runCatching { searchSongs(query, FILTER_VIDEOS) }.getOrDefault(emptyList()))
        videos.firstOrNull { it.second.accept }?.let { return Resolution(it.first, it.second.score, "video") }

        val best = songs.firstOrNull()
        return Resolution(null, bestRejected = best?.let { "${it.first.title} by ${it.first.artist}: ${it.second.reason}" }.orEmpty())
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()

        const val FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_VIDEOS = "EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_ARTISTS = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D"

        private val BULLET: JsonElement = buildJsonObject { put("text", " • ") }
        private val ITEM_TYPES = setOf("Song", "Video", "Artist", "Album", "Single", "EP")

        private val CLIENT_VERSION = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([\\d.]+)\"")
        private val DURATION = Regex("""\d+(:\d+){1,2}""")
        private val YEAR = Regex("""\d{4}""")
        private val AUDIENCE_SUFFIX = Regex("""(plays|views|monthly audience|subscribers)$""", RegexOption.IGNORE_CASE)
        private val PLAYS_SUFFIX = Regex("""plays$""", RegexOption.IGNORE_CASE)
        private val NAME_SPLIT = Regex("""\s*(?:,|&)\s*""")
        private val ARTIST_PREFIX = Regex("""^Artist\s*•\s*""")
        private val COMPILATION = Regex("""jukebox|non-?stop|best of|top \d+|\blyrical\b""", RegexOption.IGNORE_CASE)
        private val NOT_ASCII_ALNUM = Regex("[^a-z0-9]")
        private val LYRICS_ID = Regex("\"browseId\":\"(MPLY[^\"]+)\"")
        private val SOURCE_PREFIX = Regex("""^Source:\s*""", RegexOption.IGNORE_CASE)
        private val PRIMARY_ARTIST_SPLIT = Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

        private val GOOGLE_IMAGE_HOST = Regex("""googleusercontent\.com|ggpht\.com""")
        private val SIZE_WH = Regex("""=w\d+-h\d+[^&]*$""")
        private val SIZE_S = Regex("""=s\d+[^&]*$""")

        /** Rewrites a Google-hosted thumbnail URL to a crisp square image of the given size. */
        fun bigCover(url: String?, size: Int = 544): String {
            if (url.isNullOrEmpty()) return ""
            if (!GOOGLE_IMAGE_HOST.containsMatchIn(url)) return url
            return url.replace(SIZE_WH, "=w$size-h$size-l90-rj").replace(SIZE_S, "=s$size")
        }
    }
}
