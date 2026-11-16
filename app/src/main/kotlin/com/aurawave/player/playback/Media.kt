package com.aurawave.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Track
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Songs are queued as "aurawave://track/<videoId>". The real stream URL is looked up
 * only when the player opens the song (see [StreamDataSource]), because those URLs
 * expire after a few hours and a queue can outlive them.
 */
const val TRACK_SCHEME = "aurawave"

private const val EXTRA_DURATION = "durationSec"
private const val EXTRA_KIND = "kind"
private const val EXTRA_ARTISTS = "artists"
private const val EXTRA_ALBUM_ID = "albumId"
private const val EXTRA_VIDEO = "video"

fun trackUri(videoId: String): Uri = Uri.parse("$TRACK_SCHEME://track/$videoId")

/**
 * A song's address with its name on it. When YouTube cannot play the song, the name is
 * what lets the player look for the same recording elsewhere (see [StreamUrlCache]).
 * Only catalogue songs carry it: a video, an upload or an episode has no twin to look for.
 */
fun trackUri(track: Track): Uri {
    if (track.kind != Kind.SONG || track.title.isEmpty()) return trackUri(track.videoId)
    return trackUri(track.videoId).buildUpon()
        .appendQueryParameter(HINT_TITLE, track.title)
        .appendQueryParameter(HINT_ARTIST, track.artist)
        .appendQueryParameter(HINT_DURATION, track.durationSec.toString())
        .build()
}

/** What is known about a song besides its id: enough to find the same recording in another catalogue. */
data class SongHint(val title: String, val artist: String, val durationSec: Int)

fun songHint(uri: Uri): SongHint? {
    val title = uri.getQueryParameter(HINT_TITLE)?.takeIf { it.isNotEmpty() } ?: return null
    return SongHint(title, uri.getQueryParameter(HINT_ARTIST).orEmpty(), uri.getQueryParameter(HINT_DURATION)?.toIntOrNull() ?: 0)
}

private const val HINT_TITLE = "title"
private const val HINT_ARTIST = "artist"
private const val HINT_DURATION = "duration"

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(videoId)
    .setUri(trackUri(this))
    // Cached audio is filed under the video id, so it survives the stream URL changing.
    .setCustomCacheKey(videoId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.ifEmpty { null })
            .setArtworkUri(coverUrl.takeIf { it.isNotEmpty() }?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(
                Bundle().apply {
                    putInt(EXTRA_DURATION, durationSec)
                    putString(EXTRA_KIND, kind.name)
                    putString(EXTRA_ALBUM_ID, albumId)
                    putString(EXTRA_ARTISTS, Json.encodeToString(artists))
                },
            )
            .build(),
    )
    .build()

/**
 * The music video standing in for this song while the listener watches it (the Song/Video
 * switch), or null. The item stays the song: its id, name, cover, likes and history do not
 * change. Only the recording that is streamed does.
 */
val MediaItem.video: String?
    get() = mediaMetadata.extras?.getString(EXTRA_VIDEO)?.takeIf { it.isNotEmpty() && it != mediaId }

/** The recording this item is streamed from: its music video while that is showing, otherwise the song itself. */
val MediaItem.streamId: String get() = video ?: mediaId

/** The same song, streamed from its music video [videoId], or from the song's own recording when null. */
fun MediaItem.withVideo(videoId: String?): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        if (videoId == null) remove(EXTRA_VIDEO) else putString(EXTRA_VIDEO, videoId)
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build().withStreamUri()
}

/** Items arriving from a controller carry no URI (it is not sent across); give them the track URI back. */
fun MediaItem.withStreamUri(): MediaItem {
    // A music video has no twin in another catalogue, so its address carries no name.
    val uri = video?.let(::trackUri) ?: trackUri(toTrack())
    return buildUpon().setUri(uri).setCustomCacheKey(streamId).build()
}

fun MediaItem.toTrack(): Track {
    val meta = mediaMetadata
    val extras = meta.extras
    return Track(
        videoId = mediaId,
        title = meta.title?.toString().orEmpty(),
        artist = meta.artist?.toString().orEmpty(),
        artists = extras?.getString(EXTRA_ARTISTS)
            ?.let { runCatching { Json.decodeFromString<List<ArtistRef>>(it) }.getOrNull() }
            .orEmpty(),
        album = meta.albumTitle?.toString().orEmpty(),
        albumId = extras?.getString(EXTRA_ALBUM_ID).orEmpty(),
        durationSec = extras?.getInt(EXTRA_DURATION) ?: 0,
        coverUrl = meta.artworkUri?.toString().orEmpty(),
        kind = extras?.getString(EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: Kind.SONG,
    )
}
