package com.aurawave.player.ui

import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.data.db.PlaylistSummary
import kotlinx.coroutines.launch

/** One thing in the library grid: liked songs, history, downloads or a playlist. */
private class Collection(
    val key: String,
    val title: String,
    val subtitle: String,
    val covers: List<String>,
    val icon: ImageVector,
    val onOpen: () -> Unit,
)

/**
 * The library at a glance: every collection as a picture made of its covers, then saved
 * albums and followed artists. The lists themselves are one tap away.
 */
@Composable
fun LibraryOverview(
    graph: AppGraph,
    liked: List<Track>?,
    history: List<Track>?,
    playlists: List<PlaylistSummary>?,
    /** Collections as a grid of covers, or as a compact list. */
    grid: Boolean,
    onOpenPlaylists: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val downloads = LocalDownloads.current
    val albums by graph.library.savedAlbums.collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by graph.library.followedArtists.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = LocalSongActions.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    if (liked == null || history == null || playlists == null) return // still reading the library

    val downloaded = remember(downloads) { downloads.values.filter { it.done }.sortedByDescending { it.updatedAt }.map { it.track } }
    val collections = buildList {
        add(Collection("liked", "Liked songs", songCount(liked.size), liked.map { it.coverUrl }, Icons.Rounded.Favorite, onOpenLiked))
        add(Collection("history", "History", "Recently played", history.map { it.coverUrl }, Icons.Rounded.History, onOpenHistory))
        add(Collection("downloads", "Downloads", songCount(downloaded.size), downloaded.map { it.coverUrl }, Icons.Rounded.DownloadDone, onOpenDownloads))
        playlists.forEach { p ->
            add(Collection("playlist:${p.playlist.id}", p.playlist.name, songCount(p.songCount), p.coverList, Icons.AutoMirrored.Rounded.QueueMusic) { onOpenPlaylist(p.playlist.id) })
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 18.dp else 6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (grid) {
            items(collections, key = { it.key }) { CollectionTile(it) }
            item(key = "new-playlist") { NewPlaylistTile(onClick = { naming = true }) }
        } else {
            items(collections, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { CollectionRow(it) }
            item(key = "new-playlist", span = { GridItemSpan(maxLineSpan) }) {
                ActionRow(Icons.Rounded.Add, "New playlist", "Start an empty one") { naming = true }
            }
        }
        item(key = "import", span = { GridItemSpan(maxLineSpan) }) {
            ActionRow(Icons.Rounded.Link, "Import from a link", "A song, album or playlist from Spotify or YouTube", onOpenPlaylists)
        }

        if (albums.isNotEmpty()) {
            item(key = "albums", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    RowHeader("Albums", onOpenAlbums)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems(albums.take(12), key = { it.id }) { a -> AlbumTile(a) { actions.openAlbum(a) } }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "artists", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    RowHeader("Artists", onOpenArtists)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems(artists.take(12), key = { it.id }) { a -> ArtistTile(a) { actions.openArtist(ArtistRef(a.name, a.id)) } }
                    }
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create playlist",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                scope.launch { onOpenPlaylist(graph.library.createPlaylist(name)) }
            },
        )
    }
}

@Composable
private fun RowHeader(title: String, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onMore) { Text("See all") }
    }
}

@Composable
private fun CollectionTile(collection: Collection) {
    Column(Modifier.clickable(onClickLabel = "Open ${collection.title}", onClick = collection.onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Palette.Raised)) {
            val covers = collection.covers.filter { it.isNotEmpty() }.distinct()
            if (covers.isEmpty()) {
                // Nothing in it yet: its symbol, large and quiet.
                Icon(collection.icon, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(44.dp).align(Alignment.Center))
            } else {
                CoverQuilt(covers, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp).clip(CircleShape).background(Palette.Ink.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(collection.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(collection.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(collection.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A collection as one line: a small picture of its covers, its name and how much is in it. */
@Composable
private fun CollectionRow(collection: Collection) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open ${collection.title}", onClick = collection.onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
            val covers = collection.covers.filter { it.isNotEmpty() }.distinct()
            if (covers.isEmpty()) {
                Icon(collection.icon, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(24.dp))
            } else {
                CoverQuilt(covers, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(collection.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(collection.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(collection.icon, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
    }
}

/** Something to do rather than something to open: a quiet row with a symbol. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Dusk), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Palette.Haze, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewPlaylistTile(onClick: () -> Unit) {
    Column(Modifier.clickable(onClickLabel = "Create a playlist", onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Palette.Dusk),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.Haze, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("New playlist", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("Start an empty one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
