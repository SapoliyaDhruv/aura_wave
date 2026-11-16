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
 * Reads a public Spotify playlist from its embed page, on the phone. Ported from the
 * old app's /api/spotify/playlist. The embed page lists at most the first 100 songs.
 */
class Spotify(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun playlist(link: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val id = playlistId(link) ?: throw SpotifyException("Paste a Spotify playlist link, like open.spotify.com/playlist/…")
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", UA)
            .build()
        val html = http.newCall(request).execute().use { res ->
            if (res.code == 404) throw SpotifyException("Spotify could not find this playlist. Check that it is public.")
            if (!res.isSuccessful) throw IOException("Spotify returned ${res.code}")
            res.body.string()
        }
        val data = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: throw SpotifyException("Spotify changed its page and the playlist could not be read.")
        val entity: JsonElement? = json.parseToJsonElement(data).at("props", "pageProps", "state", "data", "entity")
        val songs = entity.at("trackList").arr.mapNotNull { t ->
            val title = t.at("title").str?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SpotifySong(
                title = title,
                artist = t.at("subtitle").str.orEmpty().replace(' ', ' ').replace(SPACES, " ").trim(),
                durationSec = ((t.at("duration").str?.toLongOrNull() ?: 0L) / 1000).toInt(),
            )
        }
        if (songs.isEmpty()) throw SpotifyException("This playlist has no readable songs. Make sure it is public.")
        SpotifyPlaylist(id, entity.at("name").str ?: "Spotify playlist", songs)
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val PLAYLIST_LINK = Regex("""playlist[/:]([A-Za-z0-9]{22})""")
        private val BARE_ID = Regex("""^[A-Za-z0-9]{22}$""")
        private val SPACES = Regex("""\s+""")

        fun playlistId(link: String): String? =
            PLAYLIST_LINK.find(link)?.groupValues?.get(1) ?: BARE_ID.find(link.trim())?.value

        private val YT_LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
        private val YT_BARE = Regex("""^(PL|RD|OL|VL|UU|FL|LL)[A-Za-z0-9_-]{8,}$""")

        /** The playlist id in a YouTube or YouTube Music link ("…playlist?list=PL…"), or a bare id. */
        fun youtubePlaylistId(link: String): String? =
            YT_LIST.find(link)?.groupValues?.get(1) ?: YT_BARE.find(link.trim())?.value?.removePrefix("VL")
    }
}
