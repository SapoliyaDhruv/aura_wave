package com.aurawave.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and other media browsers) can browse: the listener's liked songs,
 * recent plays, playlists, downloads and mixes. A song in a list is identified as
 * "<list id>|<video id>", so playing it can queue the rest of that list after it.
 */
class MediaTree(private val graph: AppGraph) {
    private val searches = ConcurrentHashMap<String, List<Track>>()

    fun root(): MediaItem = folder(ROOT, "AuraWave")

    /** The single item the system shows to resume playback after a restart. */
    fun recentRoot(): MediaItem = folder(RECENT_ROOT, "Recently played")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(LIKED, "Liked songs"),
            folder(RECENT, "Recently played"),
            folder(PLAYLISTS, "Playlists", playlists = true),
            folder(DOWNLOADS, "Downloads"),
            folder(MIXES, "Mixes for you", playlists = true),
        )
        parentId == RECENT_ROOT -> graph.queueStore.load()?.let { q -> q.tracks.getOrNull(q.index)?.let { listOf(it.toMediaItem()) } }.orEmpty()
        parentId == PLAYLISTS -> graph.library.playlists.first().map { p ->
            folder("$PLAYLIST_PREFIX${p.playlist.id}", p.playlist.name, artwork = p.coverUrl, subtitle = "${p.songCount} songs")
        }
        parentId == MIXES -> graph.recommender.feed.value?.let { f -> listOfNotNull(f.hero) + f.mixes }.orEmpty().map { m ->
            folder("$MIX_PREFIX${m.id}", m.name, artwork = m.picks.firstOrNull()?.track?.coverUrl, subtitle = m.subtitle)
        }
        else -> tracks(parentId).map { playable(parentId, it) }
    }

    /** The songs of a list, for browsing and for queueing a whole list from one tap. */
    suspend fun tracks(listId: String): List<Track> = when {
        listId == LIKED -> graph.library.liked.first()
        listId == RECENT -> graph.library.recentlyPlayed.first().take(100)
        listId == DOWNLOADS -> graph.downloads.songs.value.values.filter { it.done }.sortedByDescending { it.updatedAt }.map { it.track }
        listId.startsWith(PLAYLIST_PREFIX) -> listId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()?.let { graph.library.playlistSongs(it).first() }.orEmpty()
        listId.startsWith(MIX_PREFIX) -> graph.recommender.feed.value?.let { f -> listOfNotNull(f.hero) + f.mixes }
            ?.firstOrNull { it.id == listId.removePrefix(MIX_PREFIX) }?.picks?.map { it.track }.orEmpty()
        listId.startsWith(SEARCH_PREFIX) -> searches[listId.removePrefix(SEARCH_PREFIX)].orEmpty()
        else -> emptyList()
    }

    suspend fun search(query: String): List<MediaItem> {
        val results = runCatching { graph.catalog.searchSongsOnly(query) }.getOrDefault(emptyList()).take(30)
        if (searches.size > 20) searches.clear()
        searches[query] = results
        return results.map { playable("$SEARCH_PREFIX$query", it) }
    }

    /**
     * Turns one tapped song into its whole list, starting at that song. Returns null for
     * items that are not from this tree (the app's own queue).
     */
    suspend fun expand(item: MediaItem): Pair<List<Track>, Int>? {
        val id = item.mediaId
        if (SEPARATOR !in id) return null
        val listId = id.substringBeforeLast(SEPARATOR)
        val videoId = id.substringAfterLast(SEPARATOR)
        val list = tracks(listId)
        val index = list.indexOfFirst { it.videoId == videoId }
        return if (index >= 0) list to index else null
    }

    private fun folder(id: String, title: String, artwork: String? = null, subtitle: String? = null, playlists: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(if (playlists) MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private fun playable(listId: String, track: Track): MediaItem =
        track.toMediaItem().buildUpon().setMediaId("$listId$SEPARATOR${track.videoId}").build()

    companion object {
        const val ROOT = "root"
        const val RECENT_ROOT = "recent_root"
        const val LIKED = "liked"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val MIXES = "mixes"
        const val PLAYLIST_PREFIX = "playlist:"
        const val MIX_PREFIX = "mix:"
        const val SEARCH_PREFIX = "search:"
        const val SEPARATOR = "|"

        /** The video id of an item that may carry its list ("liked|abc"). */
        fun videoIdOf(mediaId: String): String = mediaId.substringAfterLast(SEPARATOR)
    }
}
