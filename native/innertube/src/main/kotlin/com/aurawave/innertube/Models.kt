package com.aurawave.innertube

import kotlinx.serialization.Serializable

/** What a recording is on YouTube Music. Songs are the official audio tracks. */
@Serializable
enum class Kind { SONG, VIDEO, UPLOAD, EPISODE }

@Serializable
data class ArtistRef(val name: String, val id: String = "")

@Serializable
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: String = "",
    val albumId: String = "",
    val year: String = "",
    val durationSec: Int = 0,
    val coverUrl: String = "",
    val kind: Kind = Kind.SONG,
    val plays: String = "",
)

data class ArtistSummary(val id: String, val name: String, val audience: String, val imageUrl: String)

data class SearchResult(
    val songs: List<Track> = emptyList(),
    val videos: List<Track> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val podcasts: List<PodcastRef> = emptyList(),
)

/** An album, single or EP. [id] is its browse id ("MPREb_..."). */
@Serializable
data class AlbumRef(
    val id: String,
    val title: String,
    val artist: String = "",
    val year: String = "",
    val coverUrl: String = "",
    /** "Album", "Single" or "EP". */
    val type: String = "Album",
)

/** A YouTube or YouTube Music playlist. [id] is the playlist id, without the "VL" browse prefix. */
@Serializable
data class PlaylistRef(
    val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val countText: String = "",
)

/** A podcast show. [id] is its browse id ("MPSPP..."). */
@Serializable
data class PodcastRef(
    val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
)

/** A mood or genre on YouTube Music's Explore page; opening it needs both [browseId] and [params]. */
data class Category(val title: String, val browseId: String, val params: String, val color: Long = 0)

sealed interface BrowseItem {
    data class Song(val track: Track) : BrowseItem
    data class Album(val album: AlbumRef) : BrowseItem
    data class Playlist(val playlist: PlaylistRef) : BrowseItem
    data class Artist(val artist: ArtistSummary) : BrowseItem
    data class Podcast(val podcast: PodcastRef) : BrowseItem
    data class Mood(val category: Category) : BrowseItem
    data class Episode(val episode: com.aurawave.innertube.Episode) : BrowseItem
}

/** A podcast episode: a [Track] of kind EPISODE whose album is the show. */
data class Episode(
    val track: Track,
    /** "Apr 13", "1d ago" */
    val date: String,
    val description: String,
)

data class PodcastPage(
    val podcast: PodcastRef,
    val description: String,
    val episodes: List<Episode>,
)

/** A titled row of items, as on YouTube Music's Explore and artist pages. */
data class Shelf(
    val title: String,
    val items: List<BrowseItem>,
    val moreBrowseId: String = "",
    val moreParams: String = "",
)

data class ArtistPage(
    val id: String,
    val name: String,
    val imageUrl: String,
    val audience: String,
    val songs: List<Track>,
    val albums: List<AlbumRef> = emptyList(),
    val singles: List<AlbumRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val similar: List<ArtistSummary> = emptyList(),
    /** Where the full list of albums (and of singles) is, when the page shows only some. */
    val allAlbums: Pair<String, String>? = null,
    val allSingles: Pair<String, String>? = null,
)

data class AlbumPage(
    val album: AlbumRef,
    val artists: List<ArtistRef>,
    /** "12 songs • 54 minutes" */
    val info: String,
    val description: String,
    val tracks: List<Track>,
    /** "Other versions", "More from this artist". */
    val more: List<Shelf>,
)

data class PlaylistPage(
    val playlist: PlaylistRef,
    val info: String,
    val description: String,
    val tracks: List<Track>,
)

data class Lyrics(val plain: String, val source: String)

/** Outcome of looking for the exact recording of a song known only by its metadata. */
data class Resolution(
    val track: Track?,
    val confidence: Double = 0.0,
    val via: String = "",
    val bestRejected: String = "",
)
