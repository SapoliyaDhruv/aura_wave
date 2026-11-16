package com.aurawave.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Importing a Spotify playlist end to end: read it, then find each song on YouTube Music.
 * Run with:  ./gradlew :innertube:test -Plive --tests '*LiveImportTest*' -i
 */
class LiveImportTest {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val tube = InnerTube(http)

    @Test
    fun spotifySongsAreFoundOnYouTubeMusic() = runBlocking {
        val list = Spotify(http).playlist("https://open.spotify.com/playlist/37i9dQZF1DX0XUfTFmNBRM")
        val songs = list.songs.take(15)
        var found = 0
        for (song in songs) {
            val r = runCatching { tube.resolve(song.title, song.artist, song.durationSec) }
            val t = r.getOrNull()?.track
            if (t != null) found++
            println("IMPORT ${if (t != null) "ok  " else "MISS"} ${song.title} / ${song.artist} / ${song.durationSec}s -> ${t?.title} / ${t?.artist} / ${t?.durationSec}s ${r.exceptionOrNull() ?: r.getOrNull()?.bestRejected.orEmpty()}")
        }
        println("IMPORT found $found of ${songs.size}")
        assertTrue(found >= songs.size * 3 / 4, "only $found of ${songs.size} songs were found")
    }

    @Test
    fun songsAndAlbumsByLink() = runBlocking {
        val spotify = Spotify(http)
        val song = spotify.song("4cOdK2wGLETKBW3PvgPWqT")
        println("IMPORT spotify song: $song -> ${tube.resolve(song.title, song.artist, song.durationSec).track}")
        assertTrue(song.title.isNotEmpty() && song.artist.isNotEmpty() && song.durationSec > 60)

        val album = spotify.collection("album", "4aawyAB9vmqN3uQ7FjRGTy")
        println("IMPORT spotify album: ${album.name}, ${album.songs.size} songs, e.g. ${album.songs.take(2)}")
        assertTrue(album.songs.size >= 5 && album.songs.all { it.artist.isNotEmpty() })

        val byId = tube.song("NJAv_7lHUIU")
        println("IMPORT youtube song: $byId")
        assertTrue(byId != null && byId.title.contains("Kesariya"))

        val albumPage = tube.album("MPREb_E4GfUXfDfhy")
        println("IMPORT youtube album: ${albumPage?.album?.title}, ${albumPage?.tracks?.size} songs")
        assertTrue(albumPage != null && albumPage.tracks.isNotEmpty())

        val asList = tube.playlist("OLAK5uy_nieQZVqMUHtrJHF8ovCkhRlqC-tkqN8C0")
        println("IMPORT album as list: '${asList?.playlist?.title}' ${asList?.tracks?.size} songs, first album '${asList?.tracks?.firstOrNull()?.album}'")
        assertTrue(asList != null && asList.tracks.isNotEmpty())
    }
}
