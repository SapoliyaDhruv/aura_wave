package com.aurawave.innertube

import kotlinx.serialization.Serializable

/** What a recording is on YouTube Music. Songs are the official audio tracks. */
@Serializable
enum class Kind { SONG, VIDEO, UPLOAD }

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
)

data class ArtistPage(
    val id: String,
    val name: String,
    val imageUrl: String,
    val audience: String,
    val songs: List<Track>,
)

data class Lyrics(val plain: String, val source: String)

/** Outcome of looking for the exact recording of a song known only by its metadata. */
data class Resolution(
    val track: Track?,
    val confidence: Double = 0.0,
    val via: String = "",
    val bestRejected: String = "",
)
