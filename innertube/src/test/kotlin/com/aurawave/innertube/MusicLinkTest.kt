package com.aurawave.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shapes of link that Share buttons really hand over. */
class MusicLinkTest {
    private val spotifyId = "37i9dQZF1DX0XUfTFmNBRM"

    @Test
    fun spotifyLinks() {
        assertEquals(MusicLink.SpotifyPlaylist(spotifyId), MusicLink.parse("https://open.spotify.com/playlist/$spotifyId?si=abc123"))
        assertEquals(MusicLink.SpotifyPlaylist(spotifyId), MusicLink.parse("https://open.spotify.com/intl-en/playlist/$spotifyId"))
        assertEquals(MusicLink.SpotifyPlaylist(spotifyId), MusicLink.parse("spotify:playlist:$spotifyId"))
        assertEquals(MusicLink.SpotifyPlaylist(spotifyId), MusicLink.parse(spotifyId))
        assertEquals(MusicLink.SpotifyAlbum("4aawyAB9vmqN3uQ7FjRGTy"), MusicLink.parse("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy?si=x"))
        assertEquals(MusicLink.SpotifyTrack("4cOdK2wGLETKBW3PvgPWqT"), MusicLink.parse("Listen to this: https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x"))
        assertEquals(MusicLink.Shortened("https://spotify.link/AbCdEf123"), MusicLink.parse("https://spotify.link/AbCdEf123"))
    }

    @Test
    fun youTubeLinks() {
        val list = "PLC0jjH3z9sx9Qj1In5f06OQl1rCi56Cnu"
        assertEquals(MusicLink.YouTubePlaylist(list), MusicLink.parse("https://music.youtube.com/playlist?list=$list&si=1"))
        assertEquals(MusicLink.YouTubePlaylist(list), MusicLink.parse("https://www.youtube.com/watch?v=NJAv_7lHUIU&list=$list"))
        assertEquals(MusicLink.YouTubePlaylist(list), MusicLink.parse("https://music.youtube.com/browse/VL$list"))
        assertEquals(MusicLink.YouTubePlaylist(list), MusicLink.parse(list))
        assertEquals(MusicLink.YouTubeAlbum("MPREb_E4GfUXfDfhy"), MusicLink.parse("https://music.youtube.com/browse/MPREb_E4GfUXfDfhy"))
        assertEquals(MusicLink.YouTubeSong("NJAv_7lHUIU"), MusicLink.parse("https://music.youtube.com/watch?v=NJAv_7lHUIU&si=abc"))
        assertEquals(MusicLink.YouTubeSong("NJAv_7lHUIU"), MusicLink.parse("https://youtu.be/NJAv_7lHUIU?si=abc"))
        assertEquals(MusicLink.YouTubeSong("NJAv_7lHUIU"), MusicLink.parse("https://www.youtube.com/shorts/NJAv_7lHUIU"))
        // Shared while the song's own radio was playing: the song is what was meant.
        assertEquals(MusicLink.YouTubeSong("NJAv_7lHUIU"), MusicLink.parse("https://music.youtube.com/watch?v=NJAv_7lHUIU&list=RDAMVMNJAv_7lHUIU"))
        // An editorial playlist's id also starts with RD, and it is a playlist.
        assertEquals(
            MusicLink.YouTubePlaylist("RDCLAK5uy_kt5FBmiCq0yT_X7QzsgBzT0QzoWZ-6UwI"),
            MusicLink.parse("https://music.youtube.com/playlist?list=RDCLAK5uy_kt5FBmiCq0yT_X7QzsgBzT0QzoWZ-6UwI&si=1"),
        )
    }

    @Test
    fun otherText() {
        assertNull(MusicLink.parse("hello"))
        assertNull(MusicLink.parse("https://example.com/page"))
        assertNull(MusicLink.parse(""))
    }
}
