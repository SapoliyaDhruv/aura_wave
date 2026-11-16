package com.aurawave.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A song as Spotify lists it: only names and a length. It is matched to a YouTube Music recording separately. */
data class SpotifySong(val title: String, val artist: String, val durationSec: Int)

data class SpotifyPlaylist(val id: String, val name: String, val songs: List<SpotifySong>)

class SpotifyException(message: String) : IOException(message)

/**
 * What a pasted link points at. Share sheets hand over many shapes of link (with tracking
 * codes, inside a sentence, shortened), so the link is read for what it names instead of
 * being matched against one exact form.
 */
sealed interface MusicLink {
    data class SpotifyPlaylist(val id: String) : MusicLink
    data class SpotifyAlbum(val id: String) : MusicLink
    data class SpotifyTrack(val id: String) : MusicLink
    data class YouTubePlaylist(val id: String) : MusicLink

    /** A YouTube Music album page ("MPREb_…"). */
    data class YouTubeAlbum(val browseId: String) : MusicLink
    data class YouTubeSong(val videoId: String) : MusicLink

    /** A shortened Spotify link: it has to be opened to learn where it leads. */
    data class Shortened(val url: String) : MusicLink

    companion object {
        private val SPOTIFY = Regex("""(playlist|album|track)[/:]([A-Za-z0-9]{22})""")
        private val SPOTIFY_SHORT = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/[^\s]+""")
        private val YT_LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
        private val YT_BROWSE_LIST = Regex("""browse/VL([A-Za-z0-9_-]{10,})""")
        private val YT_ALBUM = Regex("""browse/(MPREb_[A-Za-z0-9_-]+)""")
        private val YT_VIDEO = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
        private val BARE_SPOTIFY = Regex("""^[A-Za-z0-9]{22}$""")
        private val BARE_LIST = Regex("""^(PL|RD|OL|VL|UU|FL|LL)[A-Za-z0-9_-]{8,}$""")
        private val BARE_ALBUM = Regex("""^MPREb_[A-Za-z0-9_-]+$""")

        /** Null when the text names nothing that can be imported. */
        fun parse(text: String): MusicLink? {
            val link = text.trim()
            SPOTIFY_SHORT.find(link)?.let { return Shortened(it.value) }
            if ("spotify" in link) {
                SPOTIFY.find(link)?.let { m ->
                    val id = m.groupValues[2]
                    return when (m.groupValues[1]) {
                        "album" -> SpotifyAlbum(id)
                        "track" -> SpotifyTrack(id)
                        else -> SpotifyPlaylist(id)
                    }
                }
            }
            YT_ALBUM.find(link)?.let { return YouTubeAlbum(it.groupValues[1]) }
            YT_BROWSE_LIST.find(link)?.let { return YouTubePlaylist(it.groupValues[1]) }
            val video = YT_VIDEO.find(link)?.groupValues?.get(1)
            YT_LIST.find(link)?.groupValues?.get(1)?.let { list ->
                // A song shared while its automatic radio was playing: the listener means the song.
                val radio = list.startsWith("RD") && !list.startsWith("RDCLAK")
                return if (video != null && radio) YouTubeSong(video) else YouTubePlaylist(list)
            }
            if (video != null) return YouTubeSong(video)
            BARE_ALBUM.find(link)?.let { return YouTubeAlbum(it.value) }
            BARE_LIST.find(link)?.let { return YouTubePlaylist(it.value.removePrefix("VL")) }
            BARE_SPOTIFY.find(link)?.let { return SpotifyPlaylist(it.value) }
            return null
        }
    }
}

/**
 * Reads public Spotify playlists, albums and songs from their embed pages, on the phone.
 * Ported from the old app's /api/spotify/playlist. An embed page lists at most the first 100 songs.
 */
class Spotify(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun playlist(link: String): SpotifyPlaylist {
        val id = playlistId(link) ?: throw SpotifyException("Paste a Spotify playlist link, like open.spotify.com/playlist/…")
        return collection("playlist", id)
    }

    /** A playlist or an album ([kind] is "playlist" or "album"): its name and its songs. */
    suspend fun collection(kind: String, id: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val entity = entity(kind, id)
        // Rows name their own artists (seen live: "Pitbull, Sensato"); the album's artist fills in for a row that names none.
        val owner = if (kind == "album") entity.at("subtitle").str.orEmpty().clean() else ""
        val songs = entity.at("trackList").arr.mapNotNull { t ->
            val title = t.at("title").str?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SpotifySong(
                title = title,
                artist = t.at("subtitle").str.orEmpty().clean().ifEmpty { owner },
                durationSec = ((t.at("duration").str?.toLongOrNull() ?: 0L) / 1000).toInt(),
            )
        }
        if (songs.isEmpty()) throw SpotifyException("This $kind has no readable songs. Make sure it is public.")
        SpotifyPlaylist(id, entity.at("name").str ?: entity.at("title").str ?: "Spotify $kind", songs)
    }

    /** One song, from its link. */
    suspend fun song(id: String): SpotifySong = withContext(Dispatchers.IO) {
        val entity = entity("track", id)
        val title = (entity.at("name").str ?: entity.at("title").str)?.trim().orEmpty()
        if (title.isEmpty()) throw SpotifyException("Spotify did not say which song this is.")
        SpotifySong(
            title = title,
            artist = entity.at("artists").arr.mapNotNull { it.at("name").str }.joinToString(", ").ifEmpty { entity.at("subtitle").str.orEmpty() }.clean(),
            durationSec = ((entity.at("duration").str?.toLongOrNull() ?: 0L) / 1000).toInt(),
        )
    }

    /** Where a shortened link leads, or null when it leads nowhere that can be imported. */
    suspend fun expand(url: String): MusicLink? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { res ->
            // The link redirects to the real page; failing that, the page it shows names it.
            val landed = MusicLink.parse(res.request.url.toString())?.takeIf { it !is MusicLink.Shortened }
            landed ?: OPEN_LINK.find(res.body.string())?.value?.let { MusicLink.parse(it) }?.takeIf { it !is MusicLink.Shortened }
        }
    }

    private fun entity(kind: String, id: String): JsonElement? {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$kind/$id")
            .header("User-Agent", UA)
            .build()
        val html = http.newCall(request).execute().use { res ->
            if (res.code == 404) throw SpotifyException("Spotify could not find this $kind. Check that it is public.")
            if (!res.isSuccessful) throw IOException("Spotify returned ${res.code}")
            res.body.string()
        }
        val data = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: throw SpotifyException("Spotify changed its page and the $kind could not be read.")
        return json.parseToJsonElement(data).at("props", "pageProps", "state", "data", "entity")
            ?: throw SpotifyException("Spotify could not find this $kind. Check that it is public.")
    }

    private fun String.clean(): String = replace('\u00A0', ' ').replace(SPACES, " ").trim()

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val PLAYLIST_LINK = Regex("""playlist[/:]([A-Za-z0-9]{22})""")
        private val BARE_ID = Regex("""^[A-Za-z0-9]{22}$""")
        private val SPACES = Regex("""\s+""")
        private val OPEN_LINK = Regex("""https://open\.spotify\.com/(?:intl-[a-z-]+/)?(?:playlist|album|track)/[A-Za-z0-9]{22}""")

        fun playlistId(link: String): String? =
            PLAYLIST_LINK.find(link)?.groupValues?.get(1) ?: BARE_ID.find(link.trim())?.value

        private val YT_LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
        private val YT_BARE = Regex("""^(PL|RD|OL|VL|UU|FL|LL)[A-Za-z0-9_-]{8,}$""")

        /** The playlist id in a YouTube or YouTube Music link ("…playlist?list=PL…"), or a bare id. */
        fun youtubePlaylistId(link: String): String? =
            YT_LIST.find(link)?.groupValues?.get(1) ?: YT_BARE.find(link.trim())?.value?.removePrefix("VL")
    }
}
