package com.aurawave.player.data

import android.content.Context
import android.net.Uri
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.Kind
import com.aurawave.player.data.db.FollowedArtistRow
import com.aurawave.player.data.db.FollowedPodcastRow
import com.aurawave.player.data.db.EpisodeProgressRow
import com.aurawave.innertube.PodcastRef
import com.aurawave.player.data.db.SavedAlbumRow
import com.aurawave.innertube.Match
import com.aurawave.innertube.Track
import com.aurawave.player.data.db.HiddenRow
import com.aurawave.player.data.db.LikeRow
import com.aurawave.player.data.db.PinRow
import com.aurawave.player.data.db.PlayRow
import com.aurawave.player.data.db.SearchRow
import com.aurawave.player.data.db.SongPrefRow
import com.aurawave.player.data.db.SongRow
import com.aurawave.player.data.db.toRow
import com.aurawave.player.data.db.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backing up and restoring the library as a file, and bringing in a backup made by the
 * old AuraWave app. Restoring merges: nothing already in the library is removed or
 * replaced, so restoring the same file twice changes nothing the second time.
 */
class Backup(
    private val context: Context,
    private val library: Library,
    private val catalog: Catalog,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    sealed interface Progress {
        data object Idle : Progress
        data class Working(val step: String, val done: Int = 0, val total: Int = 0) : Progress
        data class Done(val report: Report) : Progress
        data class Failed(val message: String) : Progress
    }

    data class Report(
        val liked: Int,
        val playlistsNew: Int,
        val playlistsUpdated: Int,
        val plays: Int,
        /** "Title · Artist" of songs from the old app that could not be found on YouTube Music. */
        val unmatched: List<String>,
        val notes: List<String>,
    )

    private val state = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = state.asStateFlow()

    private val json = BackupJson
    private val dao get() = library.dao

    fun dismiss() {
        if (state.value !is Progress.Working) state.value = Progress.Idle
    }

    // ---------- export ----------

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val file = BackupFile(
            exportedAt = Instant.now().toString(),
            songs = dao.allSongs().map(SongRow::toTrack),
            liked = dao.allLikes().map { BackupLike(it.videoId, it.likedAt) },
            playlists = dao.allPlaylists().map { p ->
                BackupPlaylist(p.name, p.description, p.source, p.sourceId, p.createdAt, dao.playlistSongIds(p.id))
            },
            plays = dao.allPlays().map { BackupPlay(it.videoId, it.startedAt, it.listenedMs, it.completed) },
            hidden = dao.allHidden().map { BackupHidden(it.key, it.kind, it.label, it.at) },
            pins = dao.allPins().map { BackupPin(it.songKey, it.videoId) },
            searches = dao.allSearches(),
            lyricsOffsets = dao.allSongPrefs().filter { it.lyricsOffsetMs != 0L }.associate { it.videoId to it.lyricsOffsetMs },
            settings = BackupSettings(settings.autoplay.value, settings.tasteArtists.value, settings.tasteLanguages.value),
            savedAlbums = dao.allSavedAlbums().map { AlbumRef(it.id, it.title, it.artist, it.year, it.coverUrl, it.type) },
            followedArtists = dao.allFollowedArtists().map { BackupArtist(it.id, it.name, it.imageUrl) },
            followedPodcasts = dao.allFollowedPodcasts().map { PodcastRef(it.id, it.title, it.author, it.coverUrl) },
            episodeProgress = dao.allProgressNow().map { BackupProgress(it.videoId, it.podcastId, it.positionMs, it.durationMs, it.played) },
        )
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file for writing")
        out.bufferedWriter().use { it.write(json.encodeToString(BackupFile.serializer(), file)) }
    }

    // ---------- restore ----------

    fun restore(uri: Uri) {
        if (state.value is Progress.Working) return
        state.value = Progress.Working("Reading the backup")
        scope.launch {
            state.value = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw BackupException("Could not open that file.")
                val root = try {
                    json.parseToJsonElement(text).jsonObject
                } catch (_: SerializationException) {
                    throw BackupException("That file is not an AuraWave backup.")
                } catch (_: IllegalArgumentException) {
                    throw BackupException("That file is not an AuraWave backup.")
                }
                Progress.Done(restore(root))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                Progress.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                Progress.Failed("The backup could not be restored: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun restore(root: JsonObject): Report = when (backupKind(root)) {
        BackupKind.NATIVE -> restoreNative(json.decodeFromJsonElement(BackupFile.serializer(), root))
        BackupKind.OLD_APP -> restoreOldApp(root)
        BackupKind.OLDEST_APP -> restoreOldestApp(root)
        null -> throw BackupException("That file is not an AuraWave backup.")
    }

    private suspend fun restoreNative(file: BackupFile): Report {
        state.value = Progress.Working("Restoring your library")
        dao.insertSongsIfMissing(file.songs.map { it.toRow() })
        val liked = dao.likeIfMissing(file.liked.map { LikeRow(it.videoId, it.likedAt) }).count { it != -1L }
        val (newLists, updatedLists) = mergePlaylists(file.playlists.map { ImportedPlaylist(it.name, it.description, it.source, it.sourceId, it.songs) })
        val plays = mergePlays(file.plays.map { PlayRow(videoId = it.videoId, startedAt = it.startedAt, listenedMs = it.listenedMs, completed = it.completed) })
        dao.hideIfMissing(file.hidden.map { HiddenRow(it.key, it.kind, it.label, it.at) })
        dao.pinIfMissing(file.pins.map { PinRow(it.songKey, it.videoId, System.currentTimeMillis()) })
        val now = System.currentTimeMillis()
        file.searches.reversed().forEachIndexed { i, q -> dao.saveSearch(SearchRow(q.lowercase(), q, now - (file.searches.size - i) * 1000L)) }
        val existingPrefs = dao.allSongPrefs().map { it.videoId }.toSet()
        file.lyricsOffsets.filterKeys { it !in existingPrefs }.forEach { (v, ms) -> dao.saveSongPref(SongPrefRow(v, ms)) }
        val now2 = System.currentTimeMillis()
        dao.saveAlbumsIfMissing(file.savedAlbums.map { SavedAlbumRow(it.id, it.title, it.artist, it.year, it.coverUrl, it.type, now2) })
        dao.followIfMissing(file.followedArtists.map { FollowedArtistRow(it.id, it.name, it.imageUrl, now2) })
        dao.followPodcastsIfMissing(file.followedPodcasts.map { FollowedPodcastRow(it.id, it.title, it.author, it.coverUrl, now2) })
        dao.saveProgressIfMissing(file.episodeProgress.map { EpisodeProgressRow(it.videoId, it.podcastId, it.positionMs, it.durationMs, it.played, now2) })
        file.settings?.let { s ->
            if (settings.tasteArtists.value.isEmpty()) settings.tasteArtists.value = s.tasteArtists
            if (settings.tasteLanguages.value.isEmpty()) settings.tasteLanguages.value = s.tasteLanguages
        }
        return Report(liked, newLists, updatedLists, plays, emptyList(), emptyList())
    }

    /**
     * The old app's backup ("version 2"). Its songs carry a YouTube id when they were found
     * on YouTube Music; songs imported from Spotify may not, and are looked up here, strictly:
     * a song that cannot be matched with confidence is listed, not guessed.
     */
    private suspend fun restoreOldApp(root: JsonObject): Report {
        val pins = root.obj("pins")?.mapNotNull { (key, v) -> (v as? JsonObject)?.str("youtubeId")?.let { key to it } }?.toMap().orEmpty()

        val likedEntries = root.arr("liked").mapNotNull { it as? JsonObject }
        val playlistEntries = root.arr("playlists").mapNotNull { it as? JsonObject }
        val wanted = (likedEntries + playlistEntries.flatMap { p -> p.arr("tracks").mapNotNull { it as? JsonObject } })
            .mapNotNull(::oldTrack)
        val found = findRecordings(wanted, pins)
        val unmatched = wanted.filter { found[it.key] == null }.distinctBy { it.key }.map { "${it.title} · ${it.artist}" }

        state.value = Progress.Working("Restoring your library")
        val now = System.currentTimeMillis()
        val likedTracks = likedEntries.mapNotNull { o ->
            val t = oldTrack(o) ?: return@mapNotNull null
            val track = found[t.key] ?: return@mapNotNull null
            track to (o.num("likedAt")?.toLong() ?: now)
        }
        dao.insertSongsIfMissing(likedTracks.map { it.first.toRow() })
        // The old app kept liked songs newest first; without a date they keep that order.
        val liked = dao.likeIfMissing(
            likedTracks.mapIndexed { i, (t, at) -> LikeRow(t.videoId, if (at == now) now - i * 1000L else at) },
        ).count { it != -1L }

        val playlists = playlistEntries.map { p ->
            val tracks = p.arr("tracks").mapNotNull { (it as? JsonObject)?.let(::oldTrack) }.mapNotNull { found[it.key] }
            dao.insertSongsIfMissing(tracks.map { it.toRow() })
            ImportedPlaylist(
                name = p.str("name") ?: "Imported playlist",
                description = p.str("description").orEmpty(),
                source = if (p.str("source") == "spotify") "spotify" else "you",
                sourceId = "",
                songs = tracks.map { it.videoId },
            )
        }
        val (newLists, updatedLists) = mergePlaylists(playlists)

        val plays = mergePlays(oldPlays(root))
        importOldHidden(root)
        dao.pinIfMissing(pins.map { (k, v) -> PinRow(k, v, now) })
        root.obj("taste")?.let { taste ->
            if (settings.tasteArtists.value.isEmpty()) settings.tasteArtists.value = taste.arr("artists").mapNotNull { it.strOrNull() }
            if (settings.tasteLanguages.value.isEmpty()) settings.tasteLanguages.value = taste.arr("languages").mapNotNull { it.strOrNull() }
        }

        val notes = buildList {
            if ((root.obj("customLyrics")?.size ?: 0) > 0) add("Lyrics you edited in the old app were not brought over.")
        }
        return Report(liked, newLists, updatedLists, plays, unmatched, notes)
    }

    /** History and play counts from the old app, as plays. Durations heard are estimates: the old app kept counts, not times. */
    private suspend fun oldPlays(root: JsonObject): List<PlayRow> {
        val stats = root.obj("stats").orEmpty()
        val rows = mutableListOf<PlayRow>()
        val perSong = HashMap<String, Int>()
        val songs = mutableListOf<Track>()
        fun outcome(oldId: String?): Boolean {
            val s = stats[oldId] as? JsonObject ?: return true
            return (s.num("completes") ?: 0.0) >= (s.num("skips") ?: 0.0)
        }
        for (entry in root.arr("history")) {
            val o = entry as? JsonObject ?: continue
            val track = o.obj("track")?.let(::oldTrack)?.asTrack() ?: continue
            val at = o.num("at")?.toLong() ?: continue
            val completed = outcome(o.obj("track")?.str("id"))
            songs += track
            rows += PlayRow(videoId = track.videoId, startedAt = at, listenedMs = if (completed) track.durationSec * 1000L else SKIP_MS, completed = completed)
            perSong[track.videoId] = (perSong[track.videoId] ?: 0) + 1
        }
        // Plays older than the history the old app kept: spread between the first and last time the song was played.
        for ((_, value) in stats) {
            val s = value as? JsonObject ?: continue
            val track = s.obj("track")?.let(::oldTrack)?.asTrack() ?: continue
            val extra = ((s.num("plays") ?: 0.0).toInt() - (perSong[track.videoId] ?: 0)).coerceIn(0, MAX_ESTIMATED_PLAYS)
            if (extra == 0) continue
            val first = s.num("firstPlayed")?.toLong() ?: continue
            val last = s.num("lastPlayed")?.toLong() ?: first
            val completes = (s.num("completes") ?: 0.0).toInt()
            songs += track
            repeat(extra) { i ->
                val at = if (extra == 1) first else first + (last - first) * i / extra
                val completed = i < completes
                rows += PlayRow(videoId = track.videoId, startedAt = at, listenedMs = if (completed) track.durationSec * 1000L else SKIP_MS, completed = completed)
            }
        }
        dao.insertSongsIfMissing(songs.distinctBy { it.videoId }.map { it.toRow() })
        return rows
    }

    private suspend fun importOldHidden(root: JsonObject) {
        val hidden = root.obj("hidden") ?: return
        val now = System.currentTimeMillis()
        val rows = hidden.obj("songs").orEmpty().keys.map { HiddenRow(it, Library.KIND_SONG, it, now) } +
            hidden.obj("artists").orEmpty().keys.mapNotNull { name ->
                Library.artistKey(name).takeIf { it.isNotEmpty() }?.let { HiddenRow(it, Library.KIND_ARTIST, name, now) }
            }
        dao.hideIfMissing(rows)
    }

    /** Backups from before the old app's version 2: liked songs and playlists only. */
    private suspend fun restoreOldestApp(root: JsonObject): Report {
        val meta: List<JsonObject> = when (val m = root["favoriteMeta"]) {
            is JsonArray -> m.mapNotNull { it as? JsonObject }
            is JsonObject -> m.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        val lists = (root.arr("customPlaylists").ifEmpty { root.arr("playlists") }).mapNotNull { it as? JsonObject }
        val wanted = (meta + lists.flatMap { p -> p.arr("tracks").mapNotNull { it as? JsonObject } }).mapNotNull { oldTrack(it, trustIds = false) }
        if (wanted.isEmpty()) throw BackupException("No songs or playlists were found in this backup file.")
        val found = findRecordings(wanted, emptyMap())
        state.value = Progress.Working("Restoring your library")
        val now = System.currentTimeMillis()
        val likedTracks = meta.mapNotNull { oldTrack(it, trustIds = false) }.mapNotNull { found[it.key] }
        dao.insertSongsIfMissing(likedTracks.map { it.toRow() })
        val liked = dao.likeIfMissing(likedTracks.mapIndexed { i, t -> LikeRow(t.videoId, now - i * 1000L) }).count { it != -1L }
        val playlists = lists.map { p ->
            val tracks = p.arr("tracks").mapNotNull { (it as? JsonObject)?.let { o -> oldTrack(o, trustIds = false) } }.mapNotNull { found[it.key] }
            dao.insertSongsIfMissing(tracks.map { it.toRow() })
            ImportedPlaylist(p.str("name") ?: "Imported playlist", "", "you", "", tracks.map { it.videoId })
        }
        val (newLists, updatedLists) = mergePlaylists(playlists)
        val unmatched = wanted.filter { found[it.key] == null }.distinctBy { it.key }.map { "${it.title} · ${it.artist}" }
        return Report(liked, newLists, updatedLists, 0, unmatched, emptyList())
    }

    // ---------- shared steps ----------

    private class OldTrack(val title: String, val artist: String, val album: String, val durationSec: Int, val coverUrl: String, val videoId: String?, val kind: Kind) {
        val key: String get() = videoId ?: "?${Match.songKey(title, artist)}:${durationSec / 5}"

        fun asTrack(id: String? = videoId): Track? = id?.let {
            Track(videoId = it, title = title, artist = artist, album = album, durationSec = durationSec, coverUrl = coverUrl.ifEmpty { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }, kind = kind)
        }
    }

    /** Reads a song from the old app. [trustIds]: the oldest backups held guessed recordings, which are looked up again. */
    private fun oldTrack(o: JsonObject, trustIds: Boolean = true): OldTrack? {
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return null
        val id = o.str("id").orEmpty()
        val videoId = when {
            !trustIds && !id.startsWith("yt-") -> null
            else -> o.str("youtubeId")?.takeIf { VIDEO_ID.matches(it) } ?: id.removePrefix("yt-").takeIf { id.startsWith("yt-") && VIDEO_ID.matches(it) }
        }
        val cover = o.str("coverUrl").orEmpty().takeUnless { OLD_DEMO_COVER.containsMatchIn(it) }.orEmpty()
        return OldTrack(
            title = title,
            artist = o.str("artist")?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = o.str("album").orEmpty(),
            durationSec = o.num("duration")?.toInt() ?: 0,
            coverUrl = cover,
            videoId = videoId,
            kind = if (o.str("kind") == "video") Kind.VIDEO else Kind.SONG,
        )
    }

    /** Songs with a YouTube id are used as they are; the others are looked up on YouTube Music, a few at a time. */
    private suspend fun findRecordings(tracks: List<OldTrack>, pins: Map<String, String>): Map<String, Track> {
        val out = HashMap<String, Track>()
        val missing = ArrayList<OldTrack>()
        for (t in tracks.distinctBy { it.key }) {
            val songKey = Match.songKey(t.title, t.artist)
            val known = t.asTrack() ?: (pins[songKey] ?: dao.pinFor(songKey)?.videoId)?.let { t.asTrack(it) }
            if (known != null) out[t.key] = known else missing += t
        }
        if (missing.isEmpty()) return out

        val done = AtomicInteger(0)
        state.value = Progress.Working(FINDING, 0, missing.size)
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        val results = coroutineScope {
            missing.map { t ->
                async {
                    gate.withPermit {
                        val r = runCatching { catalog.resolve(t.title, t.artist, t.durationSec) }.getOrNull()?.track
                        state.value = Progress.Working(FINDING, done.incrementAndGet(), missing.size)
                        t.key to r?.let { found -> found.copy(coverUrl = found.coverUrl.ifEmpty { t.coverUrl }) }
                    }
                }
            }.awaitAll()
        }
        results.forEach { (key, track) -> if (track != null) out[key] = track }
        return out
    }

    private class ImportedPlaylist(val name: String, val description: String, val source: String, val sourceId: String, val songs: List<String>)

    /** A playlist with the same name (or the same import source) gets the missing songs; any other is created. */
    private suspend fun mergePlaylists(lists: List<ImportedPlaylist>): Pair<Int, Int> {
        var created = 0
        var updated = 0
        val existing = dao.allPlaylists()
        for (p in lists) {
            if (p.songs.isEmpty() && p.name.isBlank()) continue
            val match = existing.firstOrNull { (p.sourceId.isNotEmpty() && it.sourceId == p.sourceId) || it.name.equals(p.name, ignoreCase = true) }
            if (match != null) {
                if (dao.appendToPlaylist(match.id, p.songs, System.currentTimeMillis()) > 0) updated++
            } else {
                val id = library.createPlaylist(p.name, source = p.source, sourceId = p.sourceId)
                dao.appendToPlaylist(id, p.songs, System.currentTimeMillis())
                created++
            }
        }
        return created to updated
    }

    /** Adds plays that are not in the library yet (same song, same start time). */
    private suspend fun mergePlays(rows: List<PlayRow>): Int {
        if (rows.isEmpty()) return 0
        val seen = dao.allPlays().mapTo(HashSet()) { "${it.videoId}@${it.startedAt}" }
        val fresh = rows.filter { seen.add("${it.videoId}@${it.startedAt}") }
        fresh.chunked(500).forEach { dao.insertPlays(it) }
        return fresh.size
    }

    private class BackupException(message: String) : Exception(message)

    private companion object {
        const val FINDING = "Finding your songs on YouTube Music"
        const val LOOKUPS_AT_ONCE = 4
        const val SKIP_MS = 15_000L
        const val MAX_ESTIMATED_PLAYS = 200
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val OLD_DEMO_COVER = Regex("unsplash|pixabay|/covers/")
    }
}

// ---------- the file ----------

/**
 * Defaults are written out: restoring tells a backup apart by its "app" and "edition"
 * fields, and those hold their default values.
 */
internal val BackupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal enum class BackupKind { NATIVE, OLD_APP, OLDEST_APP }

/** Which app wrote a backup file, or null when it is not an AuraWave backup. */
internal fun backupKind(root: JsonObject): BackupKind? = when {
    root.str("app") == "AuraWave" && root.str("edition") == "native" -> BackupKind.NATIVE
    root.str("app") == "AuraWave" && root.num("version") == 2.0 -> BackupKind.OLD_APP
    root["favorites"] != null || root["favoriteMeta"] != null || root["customPlaylists"] != null -> BackupKind.OLDEST_APP
    else -> null
}

@Serializable
internal data class BackupFile(
    val app: String = "AuraWave",
    val edition: String = "native",
    val version: Int = 3,
    val exportedAt: String = "",
    val songs: List<Track> = emptyList(),
    val liked: List<BackupLike> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val plays: List<BackupPlay> = emptyList(),
    val hidden: List<BackupHidden> = emptyList(),
    val pins: List<BackupPin> = emptyList(),
    val searches: List<String> = emptyList(),
    val lyricsOffsets: Map<String, Long> = emptyMap(),
    val settings: BackupSettings? = null,
    val savedAlbums: List<AlbumRef> = emptyList(),
    val followedArtists: List<BackupArtist> = emptyList(),
    val followedPodcasts: List<PodcastRef> = emptyList(),
    val episodeProgress: List<BackupProgress> = emptyList(),
)

@Serializable
internal data class BackupProgress(val videoId: String, val podcastId: String, val positionMs: Long, val durationMs: Long, val played: Boolean)

@Serializable
internal data class BackupArtist(val id: String, val name: String, val imageUrl: String = "")

@Serializable
internal data class BackupLike(val videoId: String, val likedAt: Long)

@Serializable
internal data class BackupPlaylist(
    val name: String,
    val description: String = "",
    val source: String = "you",
    val sourceId: String = "",
    val createdAt: Long = 0,
    val songs: List<String> = emptyList(),
)

@Serializable
internal data class BackupPlay(val videoId: String, val startedAt: Long, val listenedMs: Long, val completed: Boolean)

@Serializable
internal data class BackupHidden(val key: String, val kind: String, val label: String = "", val at: Long = 0)

@Serializable
internal data class BackupPin(val songKey: String, val videoId: String)

@Serializable
internal data class BackupSettings(
    val autoplay: Boolean = true,
    val tasteArtists: List<String> = emptyList(),
    val tasteLanguages: List<String> = emptyList(),
)

// ---------- lenient JSON reading for files from the old app ----------

private fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.str(key: String): String? = this[key].strOrNull()
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } }
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
