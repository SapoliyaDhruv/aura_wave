package com.aurawave.player.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import com.aurawave.innertube.InnerTube
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.ArtistSummary
import com.aurawave.innertube.BrowseItem
import com.aurawave.innertube.PlaylistRef
import com.aurawave.innertube.Shelf
import com.aurawave.innertube.Track
import com.aurawave.player.data.Mix
import com.aurawave.player.playback.PlayerConnection

/** A titled row of tiles. [onMore] adds a "See all" button. */
@Composable
fun ShelfRow(shelf: Shelf, player: PlayerConnection, onMore: (() -> Unit)? = null) {
    val actions = LocalSongActions.current
    val songs = shelf.items.filterIsInstance<BrowseItem.Song>().map { it.track }
    ShelfHeader(shelf.title, onMore)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(shelf.items) { _, item ->
            when (item) {
                is BrowseItem.Song -> SongTile(item.track) { player.play(songs, songs.indexOf(item.track).coerceAtLeast(0)) }
                is BrowseItem.Album -> AlbumTile(item.album) { actions.openAlbum(item.album) }
                is BrowseItem.Playlist -> PlaylistTile(item.playlist) { actions.openOnlinePlaylist(item.playlist) }
                is BrowseItem.Artist -> ArtistTile(item.artist) { actions.openArtist(ArtistRef(item.artist.name, item.artist.id)) }
                is BrowseItem.Mood -> MoodTile(item.category.title, item.category.color) {
                    actions.openBrowse(item.category.title, item.category.browseId, item.category.params)
                }
                is BrowseItem.Podcast -> Tile(item.podcast.coverUrl, item.podcast.title, item.podcast.author) {
                    actions.openPodcast(item.podcast)
                }
                is BrowseItem.Episode -> EpisodeTile(item.episode, player)
            }
        }
    }
}

/** A shelf's heading: clear, but a step below the page's own title and the tabs, as One UI sets them. */
val ShelfTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun ShelfHeader(title: String, onMore: (() -> Unit)? = null) {
    if (title.isEmpty() && onMore == null) {
        Spacer(Modifier.height(16.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ShelfTitle, modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text("See all") }
    }
}

@Composable
fun Tile(coverUrl: String?, title: String, subtitle: String, round: Boolean = false, size: Dp = 140.dp, onClick: () -> Unit) {
    Column(Modifier.width(size).pressable(onClickLabel = "Open $title", onClick = onClick)) {
        Artwork(coverUrl, size, corner = if (round) size / 2 else 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SongTile(track: Track, subtitle: String = track.artist, onClick: () -> Unit) =
    Tile(track.coverUrl, shortTitle(track.title), subtitle, onClick = onClick)

@Composable
fun AlbumTile(album: AlbumRef, onClick: () -> Unit) =
    Tile(album.coverUrl, album.title, listOf(album.type.takeIf { it != "Album" }, album.artist.ifEmpty { null }, album.year.ifEmpty { null }).filterNotNull().joinToString(" · "), onClick = onClick)

@Composable
fun PlaylistTile(playlist: PlaylistRef, onClick: () -> Unit) =
    Tile(playlist.coverUrl, playlist.title, listOf(playlist.author, playlist.countText).filter { it.isNotEmpty() }.joinToString(" · "), onClick = onClick)

@Composable
fun ArtistTile(artist: ArtistSummary, onClick: () -> Unit) =
    Tile(artist.imageUrl, artist.name, artist.audience, round = true, size = 120.dp, onClick = onClick)

@Composable
fun MixTile(mix: Mix, onClick: () -> Unit) {
    Column(Modifier.width(136.dp).pressable(onClickLabel = "Open ${mix.name}", onClick = onClick)) {
        CoverMosaic(mix.picks.map { it.track.coverUrl }, 136.dp)
        Spacer(Modifier.height(8.dp))
        Text(mix.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Four covers in a square, for mixes and playlists made of several albums. */
@Composable
fun CoverMosaic(covers: List<String>, size: Dp) {
    val distinct = covers.filter { it.isNotEmpty() }.distinct()
    if (distinct.size < 4) {
        Artwork(distinct.firstOrNull(), size, corner = 10.dp)
        return
    }
    val half = size / 2
    Column(Modifier.clip(RoundedCornerShape(10.dp))) {
        Row {
            Artwork(distinct[0], half, corner = 0.dp)
            Artwork(distinct[1], half, corner = 0.dp)
        }
        Row {
            Artwork(distinct[2], half, corner = 0.dp)
            Artwork(distinct[3], half, corner = 0.dp)
        }
    }
}

/** A mood or genre: a coloured stripe and a name, like on YouTube Music. */
@Composable
fun MoodTile(title: String, color: Long, onClick: () -> Unit) {
    val stripe = if (color != 0L) Color(color.toInt()) else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .width(160.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Raised)
            .clickable(onClickLabel = "Open $title", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
