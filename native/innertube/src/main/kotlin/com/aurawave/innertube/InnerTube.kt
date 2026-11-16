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

    /**
     * The listener's YouTube Music login, when they signed in. It is sent only with
     * requests about their own account (library, likes, history), never with searches.
     */
    @Volatile
    var account: AccountCookies? = null

    private fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit, auth: Boolean = false): Pair<Int, String> {
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
            .apply {
                val login = account
                if (auth && login != null) {
                    header("Cookie", login.cookie)
                    header("Authorization", login.authorization(ORIGIN))
                    header("X-Goog-AuthUser", "0")
                    header("X-Origin", ORIGIN)
                }
            }
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        return http.newCall(req).execute().use { it.code to it.body.string() }
    }

    /** One retry for dropped connections, server errors and an outdated client version. */
    private suspend fun call(endpoint: String, auth: Boolean = false, body: JsonObjectBuilder.() -> Unit): JsonObject = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val (status, text) = try {
                post(endpoint, body, auth)
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
        }.distinctBy { it.id }.take(limit)
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
        val shelves = parseShelves(page)
        fun shelf(test: (String) -> Boolean) = shelves.firstOrNull { test(it.title.lowercase()) }
        val albums = shelf { it == "albums" }
        val singles = shelf { it.startsWith("singles") }
        return ArtistPage(
            id = artistId,
            name = artistName,
            imageUrl = thumbs.lastOrNull().at("url").str.orEmpty(),
            audience = runsText(header.at("monthlyListenerCount")),
            songs = (top + credited).uniqueById().take(30),
            albums = albums?.items.orEmpty().mapNotNull { (it as? BrowseItem.Album)?.album }.distinctBy { it.id },
            singles = singles?.items.orEmpty().mapNotNull { (it as? BrowseItem.Album)?.album }.distinctBy { it.id },
            playlists = shelves.filter { it.title.lowercase().let { t -> t.startsWith("playlists") || t == "featured on" } }
                .flatMap { it.items }.mapNotNull { (it as? BrowseItem.Playlist)?.playlist }.distinctBy { it.id },
            similar = shelf { it.startsWith("fans might also like") }?.items.orEmpty().mapNotNull { (it as? BrowseItem.Artist)?.artist }.distinctBy { it.id },
            allAlbums = albums?.takeIf { it.moreBrowseId.isNotEmpty() }?.let { it.moreBrowseId to it.moreParams },
            allSingles = singles?.takeIf { it.moreBrowseId.isNotEmpty() }?.let { it.moreBrowseId to it.moreParams },
        )
    }

    /** Every album (or every single) of an artist: the page behind an artist page's "More" button. */
    suspend fun artistReleases(browseId: String, params: String): List<AlbumRef> {
        val page = call("browse") { put("browseId", browseId); put("params", params) }
        return page.collect("musicTwoRowItemRenderer").mapNotNull { (parseTwoRow(it) as? BrowseItem.Album)?.album }.distinctBy { it.id }
    }

    // ---------- albums, playlists, explore ----------

    suspend fun searchAlbums(query: String): List<AlbumRef> {
        val data = call("search") { put("query", query); put("params", FILTER_ALBUMS) }
        return data.collect("musicResponsiveListItemRenderer").mapNotNull { (parseListAny(it) as? BrowseItem.Album)?.album }.distinctBy { it.id }
    }

    /** YouTube Music's own playlists first, then playlists made by people. */
    suspend fun searchPlaylists(query: String): List<PlaylistRef> = coroutineScope {
        val featured = async { runCatching { playlistSearch(query, FILTER_FEATURED_PLAYLISTS) }.getOrDefault(emptyList()) }
        val community = async { playlistSearch(query, FILTER_COMMUNITY_PLAYLISTS) }
        (featured.await() + community.await()).distinctBy { it.id }
    }

    private suspend fun playlistSearch(query: String, filter: String): List<PlaylistRef> {
        val data = call("search") { put("query", query); put("params", filter) }
        return data.collect("musicResponsiveListItemRenderer").mapNotNull { (parseListAny(it) as? BrowseItem.Playlist)?.playlist }
    }

    suspend fun searchPodcasts(query: String): List<PodcastRef> {
        val data = call("search") { put("query", query); put("params", FILTER_PODCASTS) }
        return data.collect("musicResponsiveListItemRenderer").mapNotNull { (parseListAny(it) as? BrowseItem.Podcast)?.podcast }.distinctBy { it.id }
    }

    suspend fun album(browseId: String): AlbumPage? {
        val page = call("browse") { put("browseId", browseId) }
        val header = page.collect("musicResponsiveHeaderRenderer").firstOrNull()
            ?: page.collect("musicDetailHeaderRenderer").firstOrNull()
            ?: return null
        val title = runsText(header.at("title"))
        val parts = subtitleParts(header.at("subtitle", "runs").arr)
        val artists = parseByline(header.at("straplineTextOne", "runs").arr).artists
            .ifEmpty { parseByline(header.at("subtitle", "runs").arr).artists }
        val cover = bestThumb(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr)
        val album = AlbumRef(
            id = browseId,
            title = title,
            artist = artists.joinToString(", ") { it.name },
            year = parts.firstOrNull { YEAR.matches(it) }.orEmpty(),
            coverUrl = cover,
            type = parts.firstOrNull { it in ALBUM_TYPES } ?: "Album",
        )
        val shelf = page.collect("musicShelfRenderer").firstOrNull()
        val tracks = shelf.at("contents").arr.mapNotNull { parseListItem(it.at("musicResponsiveListItemRenderer")) }.map { t ->
            t.copy(
                // Album rows carry no artwork of their own and sometimes no artist.
                coverUrl = if (t.coverUrl.contains("i.ytimg.com")) cover.ifEmpty { t.coverUrl } else t.coverUrl,
                artists = t.artists.ifEmpty { artists },
                artist = if (t.artists.isEmpty() && artists.isNotEmpty()) album.artist else t.artist,
                album = title,
                albumId = browseId,
                year = album.year,
                kind = Kind.SONG,
            )
        }.uniqueById()
        return AlbumPage(
            album = album,
            artists = artists,
            info = runsText(header.at("secondSubtitle")),
            description = runsText(header.collect("musicDescriptionShelfRenderer").firstOrNull().at("description")),
            tracks = tracks,
            more = parseShelves(page.at("contents", "twoColumnBrowseResultsRenderer", "secondaryContents") ?: page)
                .filter { s -> s.items.any { it is BrowseItem.Album } },
        )
    }

    /** A playlist and its songs, following YouTube's paging up to [maxTracks]. */
    suspend fun playlist(playlistId: String, maxTracks: Int = 1000): PlaylistPage? {
        val id = playlistId.removePrefix("VL")
        val page = call("browse") { put("browseId", "VL$id") }
        val header = page.collect("musicResponsiveHeaderRenderer").firstOrNull()
            ?: page.collect("musicDetailHeaderRenderer").firstOrNull()
        val tracks = page.collect("musicResponsiveListItemRenderer").mapNotNull(::parseListItem).toMutableList()
        var token = continuationToken(page)
        var pages = 0
        while (token != null && tracks.size < maxTracks && pages++ < MAX_PAGES) {
            val more = call("browse") { put("continuation", token) }
            val found = more.collect("musicResponsiveListItemRenderer").mapNotNull(::parseListItem)
            if (found.isEmpty()) break
            tracks += found
            token = continuationToken(more)
        }
        val title = runsText(header.at("title"))
        if (header == null && tracks.isEmpty()) return null
        val strap = runsText(header.at("straplineTextOne"))
            .ifEmpty { header.at("facepile", "avatarStackViewModel", "text", "content").str.orEmpty() }
        val parts = subtitleParts(header.at("subtitle", "runs").arr)
        return PlaylistPage(
            playlist = PlaylistRef(
                id = id,
                title = title.ifEmpty { "Playlist" },
                author = strap.ifEmpty { parts.drop(1).firstOrNull { !YEAR.matches(it) }.orEmpty() },
                coverUrl = bestThumb(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr)
                    .ifEmpty { bestThumb(header.at("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails").arr) },
            ),
            info = runsText(header.at("secondSubtitle")),
            description = runsText(header.collect("musicDescriptionShelfRenderer").firstOrNull().at("description"))
                .ifEmpty { runsText(header.at("description")) },
            tracks = tracks.uniqueById().take(maxTracks),
        )
    }

    private fun continuationToken(page: JsonElement): String? =
        page.collect("continuationCommand").firstOrNull().at("token").str

    /**
     * The rows of a YouTube Music browse page: Explore ("FEmusic_explore"), charts,
     * moods and genres, new releases, one mood's playlists, or a "More" page.
     */
    suspend fun shelves(browseId: String, params: String = ""): List<Shelf> {
        val page = call("browse") {
            put("browseId", browseId)
            if (params.isNotEmpty()) put("params", params)
        }
        return parseShelves(page)
    }

    private fun parseShelves(page: JsonElement): List<Shelf> =
        page.collectAny(SHELF_KEYS).mapNotNull { (key, s) ->
            val title: String
            var moreId = ""
            var moreParams = ""
            if (key == "gridRenderer") {
                title = runsText(s.at("header", "gridHeaderRenderer", "title"))
            } else {
                val h = s.at("header", "musicCarouselShelfBasicHeaderRenderer")
                title = runsText(h.at("title"))
                val more = h.at("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")
                    ?: h.at("title", "runs").arr.firstOrNull().at("navigationEndpoint", "browseEndpoint")
                moreId = more.at("browseId").str.orEmpty()
                moreParams = more.at("params").str.orEmpty()
            }
            val items = (s.at("contents").arr + s.at("items").arr).mapNotNull { item ->
                item.at("musicTwoRowItemRenderer")?.let(::parseTwoRow)
                    ?: item.at("musicResponsiveListItemRenderer")?.let(::parseListAny)
                    ?: item.at("musicNavigationButtonRenderer")?.let(::parseCategory)
                    ?: item.at("musicMultiRowListItemRenderer")?.let { parseEpisode(it)?.let(BrowseItem::Episode) }
            }
            if (items.isEmpty()) null else Shelf(title, items, moreId, moreParams)
        }

    // ---------- the listener's account (needs [account]) ----------

    private fun requireAccount() {
        if (account == null) throw InnerTubeException("Not signed in", 401)
    }

    /** Name, handle and picture of the signed-in account. */
    suspend fun accountInfo(): AccountInfo? {
        requireAccount()
        val data = call("account/account_menu", auth = true) {}
        val header = data.collect("activeAccountHeaderRenderer").firstOrNull() ?: return null
        return AccountInfo(
            name = runsText(header.at("accountName")),
            handle = runsText(header.at("channelHandle")).ifEmpty { runsText(header.at("email")) },
            photoUrl = header.at("accountPhoto", "thumbnails").arr.lastOrNull().at("url").str.orEmpty(),
        )
    }

    /** Every song in the account's Liked music. */
    suspend fun likedSongs(max: Int = 5000): List<Track> = authPlaylistTracks("LM", max)

    /** The playlists in the account's library (made or saved there). */
    suspend fun libraryPlaylists(): List<PlaylistRef> {
        requireAccount()
        val page = call("browse", auth = true) { put("browseId", "FEmusic_liked_playlists") }
        val items = page.collect("musicTwoRowItemRenderer").mapNotNull { (parseTwoRow(it) as? BrowseItem.Playlist)?.playlist }.toMutableList()
        var token = continuationToken(page)
        var pages = 0
        while (token != null && pages++ < MAX_PAGES) {
            val more = call("browse", auth = true) { put("continuation", token) }
            val found = more.collect("musicTwoRowItemRenderer").mapNotNull { (parseTwoRow(it) as? BrowseItem.Playlist)?.playlist }
            if (found.isEmpty()) break
            items += found
            token = continuationToken(more)
        }
        // "Liked music" and "Episodes for later" are synced on their own.
        return items.filter { it.id != "LM" && it.id != "SE" }.distinctBy { it.id }
    }

    /** A playlist's songs as the signed-in account sees it (private playlists included). */
    suspend fun authPlaylistTracks(playlistId: String, max: Int = 5000): List<Track> {
        requireAccount()
        val id = playlistId.removePrefix("VL")
        val page = call("browse", auth = true) { put("browseId", "VL$id") }
        val tracks = page.collect("musicResponsiveListItemRenderer").mapNotNull(::parseListItem).toMutableList()
        var token = continuationToken(page)
        var pages = 0
        while (token != null && tracks.size < max && pages++ < MAX_PAGES * 3) {
            val more = call("browse", auth = true) { put("continuation", token) }
            val found = more.collect("musicResponsiveListItemRenderer").mapNotNull(::parseListItem)
            if (found.isEmpty()) break
            tracks += found
            token = continuationToken(more)
        }
        return tracks.uniqueById()
    }

    suspend fun savedAlbums(): List<AlbumRef> {
        requireAccount()
        val page = call("browse", auth = true) { put("browseId", "FEmusic_liked_albums") }
        return page.collect("musicTwoRowItemRenderer").mapNotNull { (parseTwoRow(it) as? BrowseItem.Album)?.album }
    }

    suspend fun subscribedArtists(): List<ArtistSummary> {
        requireAccount()
        val page = call("browse", auth = true) { put("browseId", "FEmusic_library_corpus_artists") }
        return page.collect("musicResponsiveListItemRenderer").mapNotNull { (parseListAny(it) as? BrowseItem.Artist)?.artist }
    }

    suspend fun like(videoId: String) {
        requireAccount()
        call("like/like", auth = true) { putJsonObject("target") { put("videoId", videoId) } }
    }

    suspend fun removeLike(videoId: String) {
        requireAccount()
        call("like/removelike", auth = true) { putJsonObject("target") { put("videoId", videoId) } }
    }

    /** Makes a private playlist in the account and returns its id. */
    suspend fun createPlaylist(title: String, videoIds: List<String>): String? {
        requireAccount()
        val data = call("playlist/create", auth = true) {
            put("title", title)
            put("privacyStatus", "PRIVATE")
            put("videoIds", kotlinx.serialization.json.JsonArray(videoIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        return data.at("playlistId").str
    }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>) {
        requireAccount()
        if (videoIds.isEmpty()) return
        call("browse/edit_playlist", auth = true) {
            put("playlistId", playlistId.removePrefix("VL"))
            put(
                "actions",
                kotlinx.serialization.json.JsonArray(
                    videoIds.map { id ->
                        buildJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", id)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    },
                ),
            )
        }
    }

    /**
     * Tells YouTube the account played a song, so it shows in YouTube Music's history and
     * shapes its recommendations. Returns false when YouTube gave no way to report it.
     */
    suspend fun addToHistory(videoId: String): Boolean {
        requireAccount()
        val data = call("player", auth = true) { put("videoId", videoId) }
        val url = data.at("playbackTracking", "videostatsPlaybackUrl", "baseUrl").str ?: return false
        val cpn = (1..16).map { CPN_ALPHABET.random() }.joinToString("")
        val login = account ?: return false
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$url&ver=2&c=WEB_REMIX&cpn=$cpn")
                .header("User-Agent", UA)
                .header("Cookie", login.cookie)
                .header("Authorization", login.authorization(ORIGIN))
                .header("X-Goog-AuthUser", "0")
                .header("X-Origin", ORIGIN)
                .build()
            runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
    }

    // ---------- podcasts ----------

    /** A show and its episodes, newest first. */
    suspend fun podcast(browseId: String, maxEpisodes: Int = 200): PodcastPage? {
        val page = call("browse") { put("browseId", browseId) }
        val header = page.collect("musicResponsiveHeaderRenderer").firstOrNull() ?: return null
        val show = PodcastRef(
            id = browseId,
            title = runsText(header.at("title")),
            author = runsText(header.at("straplineTextOne")),
            coverUrl = bestThumb(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr),
        )
        val episodes = page.collect("musicMultiRowListItemRenderer").mapNotNull { parseEpisode(it, show) }.toMutableList()
        var token = continuationToken(page)
        var pages = 0
        while (token != null && episodes.size < maxEpisodes && pages++ < MAX_PAGES) {
            val more = call("browse") { put("continuation", token) }
            val found = more.collect("musicMultiRowListItemRenderer").mapNotNull { parseEpisode(it, show) }
            if (found.isEmpty()) break
            episodes += found
            token = continuationToken(more)
        }
        return PodcastPage(
            podcast = show,
            description = runsText(header.collect("musicDescriptionShelfRenderer").firstOrNull().at("description")),
            episodes = episodes.distinctBy { it.track.videoId },
        )
    }

    /** An episode row (musicMultiRowListItemRenderer). [show] is known on a show's own page. */
    private fun parseEpisode(r: JsonElement?, show: PodcastRef? = null): Episode? {
        val videoId = r.at("onTap", "watchEndpoint", "videoId").str ?: return null
        val title = runsText(r.at("title"))
        if (title.isEmpty()) return null
        val showRun = r.at("secondTitle", "runs").arr.firstOrNull()
        val showName = show?.title ?: showRun.at("text").str.orEmpty()
        val showId = show?.id ?: showRun.at("navigationEndpoint", "browseEndpoint", "browseId").str.orEmpty()
        val parts = subtitleParts(r.at("subtitle", "runs").arr)
        val progress = r.at("playbackProgress", "musicPlaybackProgressRenderer")
        val durationText = subtitleParts(progress.at("durationText", "runs").arr).firstOrNull().orEmpty()
        val thumbs = r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr
        val author = show?.author?.ifEmpty { null } ?: showName
        return Episode(
            track = Track(
                videoId = videoId,
                title = title,
                artist = author.ifEmpty { "Podcast" },
                album = showName,
                albumId = showId,
                durationSec = parseSpokenDuration(durationText),
                coverUrl = thumbs.lastOrNull().at("url").str ?: show?.coverUrl.orEmpty(),
                kind = Kind.EPISODE,
                plays = parts.firstOrNull { AUDIENCE_SUFFIX.containsMatchIn(it) }.orEmpty(),
                year = parts.lastOrNull { !AUDIENCE_SUFFIX.containsMatchIn(it) }.orEmpty(),
            ),
            date = parts.lastOrNull { !AUDIENCE_SUFFIX.containsMatchIn(it) }.orEmpty(),
            description = runsText(r.at("description")),
        )
    }

    /** "1 hr 47 min", "52 min", "45 sec" to seconds. */
    private fun parseSpokenDuration(text: String): Int {
        var total = 0
        SPOKEN_DURATION.findAll(text).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            total += when (m.groupValues[2].lowercase().first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }
        return total
    }

    private fun thumbsOf(r: JsonElement?): List<JsonElement> =
        r.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr
            .ifEmpty { r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arr }

    /** "Album • Arijit Singh • 2023" as its parts. */
    private fun subtitleParts(runs: List<JsonElement>): List<String> =
        runs.joinToString("") { it.at("text").str.orEmpty() }.split(" • ", "•").map { it.trim() }.filter { it.isNotEmpty() }

    /** A tile (musicTwoRowItemRenderer): album, playlist, artist, podcast or a song/video. */
    private fun parseTwoRow(r: JsonElement?): BrowseItem? {
        val title = runsText(r.at("title"))
        if (title.isEmpty()) return null
        val subRuns = r.at("subtitle", "runs").arr
        val thumbs = thumbsOf(r)
        r.at("navigationEndpoint", "watchEndpoint", "videoId").str?.let { videoId ->
            return BrowseItem.Song(toTrack(videoId, title, parseByline(subRuns), thumbs, videoTypeOf(r)))
        }
        val id = r.at("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        return browseItem(id, pageType(r), title, subtitleParts(subRuns), thumbs)
    }

    /** A list row (musicResponsiveListItemRenderer) that may be a song or a link to an album, playlist, artist or podcast. */
    private fun parseListAny(r: JsonElement?): BrowseItem? {
        parseListItem(r)?.let { return BrowseItem.Song(it) }
        val id = r.at("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        val cols = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        val title = runsText(cols.firstOrNull())
        if (title.isEmpty()) return null
        val subRuns = mutableListOf<JsonElement>()
        cols.drop(1).forEachIndexed { i, c ->
            if (i > 0) subRuns += BULLET
            subRuns += c.at("runs").arr
        }
        return browseItem(id, pageType(r), title, subtitleParts(subRuns), thumbsOf(r))
    }

    private fun parseCategory(r: JsonElement?): BrowseItem? {
        val endpoint = r.at("clickCommand", "browseEndpoint") ?: return null
        if (endpoint.at("browseId").str != MOODS_CATEGORY) return null
        return BrowseItem.Mood(
            Category(
                title = runsText(r.at("buttonText")),
                browseId = MOODS_CATEGORY,
                params = endpoint.at("params").str.orEmpty(),
                color = r.at("solid", "leftStripeColor").str?.toLongOrNull() ?: 0,
            ),
        )
    }

    private fun browseItem(id: String, type: String?, title: String, parts: List<String>, thumbs: List<JsonElement>): BrowseItem? {
        val cover = bestThumb(thumbs)
        return when {
            type == "MUSIC_PAGE_TYPE_ALBUM" || id.startsWith("MPREb") -> {
                val rest = parts.filter { it !in ALBUM_TYPES && !YEAR.matches(it) && !AUDIENCE_SUFFIX.containsMatchIn(it) }
                BrowseItem.Album(
                    AlbumRef(
                        id = id,
                        title = title,
                        artist = rest.joinToString(", "),
                        year = parts.firstOrNull { YEAR.matches(it) }.orEmpty(),
                        coverUrl = cover,
                        type = parts.firstOrNull { it in ALBUM_TYPES } ?: "Album",
                    ),
                )
            }
            type == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE" || id.startsWith("MPSP") -> {
                val rest = parts.filter { it != "Podcast" }
                BrowseItem.Podcast(PodcastRef(id, title, rest.firstOrNull().orEmpty(), cover))
            }
            type == "MUSIC_PAGE_TYPE_PLAYLIST" || id.startsWith("VL") -> {
                val rest = parts.dropWhile { it in PLAYLIST_TYPES }
                BrowseItem.Playlist(
                    PlaylistRef(
                        id = id.removePrefix("VL"),
                        title = title,
                        author = rest.firstOrNull { !COUNT.containsMatchIn(it) && !AUDIENCE_SUFFIX.containsMatchIn(it) }.orEmpty(),
                        coverUrl = cover,
                        countText = rest.firstOrNull { COUNT.containsMatchIn(it) }.orEmpty(),
                    ),
                )
            }
            type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL" || id.startsWith("UC") ->
                BrowseItem.Artist(
                    ArtistSummary(
                        id = id,
                        name = title,
                        audience = parts.filter { it != "Artist" }.joinToString(" • "),
                        imageUrl = bigCover(thumbs.lastOrNull().at("url").str, 400),
                    ),
                )
            else -> null
        }
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
        const val FILTER_ALBUMS = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_PODCASTS = "EgWKAQJQAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_EPISODES = "EgWKAQJIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_FEATURED_PLAYLISTS = "EgeKAQQoADgBagoQAxAEEAkQChAF"
        const val FILTER_COMMUNITY_PLAYLISTS = "EgeKAQQoAEABagoQAxAEEAkQChAF"

        const val EXPLORE = "FEmusic_explore"
        const val CHARTS = "FEmusic_charts"
        const val MOODS_AND_GENRES = "FEmusic_moods_and_genres"
        const val NEW_RELEASES = "FEmusic_new_releases"
        const val PODCASTS = "FEmusic_podcasts"
        private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private val SPOKEN_DURATION = Regex("""(\d+)\s*(hr|hour|min|sec|h|m|s)""", RegexOption.IGNORE_CASE)
        private const val MOODS_CATEGORY = "FEmusic_moods_and_genres_category"
        private const val MAX_PAGES = 20
        private val SHELF_KEYS = setOf("musicCarouselShelfRenderer", "gridRenderer")
        private val ALBUM_TYPES = setOf("Album", "Single", "EP")
        private val PLAYLIST_TYPES = setOf("Playlist", "Chart", "Mix")
        private val COUNT = Regex("""^\d[\d,.]*\s*(songs?|tracks?|episodes?|videos?)$""", RegexOption.IGNORE_CASE)

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
