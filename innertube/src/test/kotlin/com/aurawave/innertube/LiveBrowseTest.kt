package com.aurawave.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Albums, playlists, artist sections, search filters and Explore against the real YouTube Music. Run with -Plive. */
class LiveBrowseTest {
    private val tube = InnerTube(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    )

    @Test
    fun albumsSearchAndPage() = runBlocking {
        val albums = tube.searchAlbums("aashiqui 2")
        albums.take(3).forEach { println("album: $it") }
        val first = albums.first()
        assertTrue(first.id.startsWith("MPREb"))
        assertTrue(first.title.isNotEmpty() && first.coverUrl.startsWith("https://"))

        val page = assertNotNull(tube.album(first.id))
        println("album page: ${page.album} / ${page.info} / ${page.artists} / more=${page.more.map { it.title }}")
        page.tracks.take(3).forEach { println("  ${it.videoId} ${it.title} / ${it.artist} / ${it.coverUrl.take(50)}") }
        assertTrue(page.tracks.size >= 5)
        assertTrue(page.tracks.all { it.albumId == first.id && it.coverUrl.startsWith("https://") })
    }

    @Test
    fun playlistsSearchAndLongPlaylistPages() = runBlocking {
        val lists = tube.searchPlaylists("arijit singh")
        lists.take(4).forEach { println("playlist: $it") }
        assertTrue(lists.size >= 5)

        val page = assertNotNull(tube.playlist("PLC0jjH3z9sx9Qj1In5f06OQl1rCi56Cnu"))
        println("playlist page: ${page.playlist} / ${page.info} / ${page.tracks.size} tracks")
        assertTrue(page.tracks.size > 150, "paging should go past the first 100 songs")

        val editorial = assertNotNull(tube.playlist(lists.first().id))
        println("editorial: ${editorial.playlist.title} ${editorial.tracks.size} tracks, first ${editorial.tracks.firstOrNull()}")
        assertTrue(editorial.tracks.isNotEmpty())
    }

    @Test
    fun artistSections(): Unit = runBlocking {
        val page = assertNotNull(tube.artist(id = "UCDxKh1gFWeYsqePvgVzmPoQ"))
        println("artist ${page.name}: ${page.songs.size} songs, ${page.albums.size} albums, ${page.singles.size} singles, ${page.playlists.size} playlists, similar=${page.similar.map { it.name }}")
        println("  all albums at ${page.allAlbums}")
        assertTrue(page.albums.isNotEmpty() && page.singles.isNotEmpty() && page.similar.isNotEmpty())
        page.allAlbums?.let { (id, params) ->
            val all = tube.artistReleases(id, params)
            println("  all albums: ${all.size}, e.g. ${all.take(3).map { it.title }}")
            assertTrue(all.size >= page.albums.size)
        }
    }

    @Test
    fun exploreChartsMoodsNewReleases() = runBlocking {
        for (id in listOf(InnerTube.EXPLORE, InnerTube.CHARTS, InnerTube.MOODS_AND_GENRES, InnerTube.NEW_RELEASES)) {
            val shelves = tube.shelves(id)
            println("$id: " + shelves.joinToString { "${it.title} (${it.items.size} ${it.items.firstOrNull()?.javaClass?.simpleName})" })
            assertTrue(shelves.isNotEmpty(), "$id had no shelves")
        }
        val mood = tube.shelves(InnerTube.MOODS_AND_GENRES).flatMap { it.items }.filterIsInstance<BrowseItem.Mood>().first()
        val inside = tube.shelves(mood.category.browseId, mood.category.params)
        println("mood ${mood.category.title}: " + inside.joinToString { "${it.title} (${it.items.size})" })
        assertTrue(inside.flatMap { it.items }.any { it is BrowseItem.Playlist })
    }

    @Test
    fun podcastSearchShowAndHome() = runBlocking {
        val shows = tube.searchPodcasts("the ranveer show")
        shows.take(3).forEach { println("podcast: $it") }
        assertTrue(shows.isNotEmpty())

        val page = assertNotNull(tube.podcast(shows.first().id))
        println("show: ${page.podcast} with ${page.episodes.size} episodes")
        page.episodes.take(2).forEach { println("  ${it.track.videoId} ${it.track.title.take(50)} / ${it.date} / ${it.track.durationSec}s / ${it.track.album}") }
        assertTrue(page.episodes.isNotEmpty())
        assertTrue(page.episodes.all { it.track.kind == Kind.EPISODE && it.track.durationSec > 0 })

        val home = tube.shelves(InnerTube.PODCASTS)
        println("podcasts home: " + home.joinToString { "${it.title} (${it.items.size})" })
        val episode = home.flatMap { it.items }.filterIsInstance<BrowseItem.Episode>().first().episode
        println("  e.g. ${episode.track.title.take(40)} from ${episode.track.album} (${episode.track.albumId})")
        assertTrue(episode.track.albumId.startsWith("MPSP"))
    }
}

class LiveSpotifyTest {
    @Test
    fun readsPublicPlaylist() = runBlocking {
        val list = Spotify(OkHttpClient()).playlist("https://open.spotify.com/playlist/37i9dQZF1DX0XUfTFmNBRM?si=x")
        println("spotify: ${list.name}, ${list.songs.size} songs, e.g. ${list.songs.take(2)}")
        assertTrue(list.songs.size >= 20)
        assertTrue(list.songs.all { it.title.isNotEmpty() && it.artist.isNotEmpty() && ' ' !in it.artist })
    }

    @Test
    fun linkParsing() {
        assertTrue(Spotify.playlistId("https://open.spotify.com/playlist/37i9dQZF1DX0XUfTFmNBRM?si=abc") == "37i9dQZF1DX0XUfTFmNBRM")
        assertTrue(Spotify.youtubePlaylistId("https://music.youtube.com/playlist?list=RDCLAK5uy_kt5FBmiCq0yT_X7QzsgBzT0QzoWZ-6UwI&si=1") == "RDCLAK5uy_kt5FBmiCq0yT_X7QzsgBzT0QzoWZ-6UwI")
        assertTrue(Spotify.youtubePlaylistId("https://www.youtube.com/watch?v=abc&list=PLC0jjH3z9sx9Qj1In5f06OQl1rCi56Cnu") == "PLC0jjH3z9sx9Qj1In5f06OQl1rCi56Cnu")
        assertTrue(Spotify.youtubePlaylistId("hello") == null)
    }
}

class LiveLoudnessTest {
    @Test
    fun loudSongsGetTurnedDown() = runBlocking {
        val loudness = Loudness(OkHttpClient())
        val loud = loudness.gainDb("YQRZho6n9TA") // First Class: mastered loud
        val tumHiHo = loudness.gainDb("NUo8CKI34o4")
        println("gain: First Class $loud dB, Tum Hi Ho $tumHiHo dB")
        assertNotNull(loud)
        assertTrue(loud < -3)
    }
}
