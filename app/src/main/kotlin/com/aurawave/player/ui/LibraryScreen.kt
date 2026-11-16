package com.aurawave.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboard
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.MusicLink
import com.aurawave.player.data.PlaylistImporter
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.data.db.PlaylistSummary
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.launch

private enum class Shelf(val label: String) {
    Overview("Overview"),
    Liked("Liked songs"),
    Playlists("Playlists"),
    Albums("Albums"),
    Artists("Artists"),
    Downloads("Downloads"),
    History("History"),
}

@Composable
fun LibraryScreen(
    graph: AppGraph,
    player: PlayerConnection,
    onOpenPlaylist: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.Overview) }
    val liked by graph.library.liked.collectAsStateWithLifecycle(initialValue = null)
    val history by graph.library.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val playlists by graph.library.playlists.collectAsStateWithLifecycle(initialValue = null)

    val grid by graph.settings.libraryGrid.flow.collectAsStateWithLifecycle()
    // "Import from a link" on the overview: the playlists open with the link box already up.
    var importOnOpen by remember { mutableStateOf(false) }
    // The library opens on everything at a glance; a collection opens over it, and Back returns.
    BackHandler(enabled = shelf != Shelf.Overview) { shelf = Shelf.Overview }

    Column(Modifier.fillMaxSize()) {
        if (shelf == Shelf.Overview) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { graph.settings.libraryGrid.value = !grid }) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = if (grid) "Show as a list" else "Show as a grid",
                    )
                }
                IconButton(onClick = onOpenStats) { Icon(Icons.Rounded.Insights, contentDescription = "Listening stats") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shelf = Shelf.Overview }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to your library") }
                Text(shelf.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }

        when (shelf) {
            Shelf.Overview -> LibraryOverview(
                graph = graph,
                liked = liked,
                history = history,
                playlists = playlists,
                grid = grid,
                onOpenPlaylists = {
                    importOnOpen = true
                    shelf = Shelf.Playlists
                },
                onOpenLiked = { shelf = Shelf.Liked },
                onOpenHistory = { shelf = Shelf.History },
                onOpenDownloads = { shelf = Shelf.Downloads },
                onOpenAlbums = { shelf = Shelf.Albums },
                onOpenArtists = { shelf = Shelf.Artists },
                onOpenPlaylist = onOpenPlaylist,
            )
            Shelf.Liked -> SongShelf(
                songs = liked,
                player = player,
                emptyTitle = "No liked songs yet",
                emptyBody = "Tap the heart on a song you love and it is kept here.",
            )
            Shelf.History -> SongShelf(
                songs = history,
                player = player,
                emptyTitle = "Nothing played yet",
                emptyBody = "Songs you listen to appear here, most recent first.",
            )
            Shelf.Playlists -> PlaylistShelf(graph, player, playlists, onOpenPlaylist, askForLink = importOnOpen, onAsked = { importOnOpen = false })
            Shelf.Albums -> AlbumShelf(graph)
            Shelf.Downloads -> DownloadShelf(graph, player)
            Shelf.Artists -> ArtistShelf(graph)
        }
    }
}

@Composable
private fun SongShelf(songs: List<Track>?, player: PlayerConnection, emptyTitle: String, emptyBody: String) {
    val currentId = player.current?.mediaId
    when {
        songs == null -> Unit // still reading the library
        songs.isEmpty() -> MessageBox(title = emptyTitle, body = emptyBody)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        songCount(songs.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { player.play(songs.shuffled()) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                    Button(onClick = { player.play(songs) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
            }
            itemsIndexed(songs, key = { _, t -> t.videoId }) { i, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.videoId == currentId,
                    onClick = { player.play(songs, i) },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }
    }
}

@Composable
private fun DownloadShelf(graph: AppGraph, player: PlayerConnection) {
    val downloads = LocalDownloads.current
    val used by graph.downloads.bytesUsed.collectAsStateWithLifecycle()
    val done = remember(downloads) { downloads.values.filter { it.done }.sortedByDescending { it.updatedAt }.map { it.track } }
    val pending = remember(downloads) { downloads.values.filter { !it.done } }
    if (downloads.isEmpty()) {
        MessageBox(
            title = "Nothing downloaded yet",
            body = "Download songs, albums or playlists from their menus to play them with no connection.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (pending.isNotEmpty()) {
            val failed = pending.count { it.failed }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        val waiting = pending.size - failed
                        if (waiting > 0) append("Downloading ${songCount(waiting)}")
                        if (failed > 0) { if (isNotEmpty()) append(" · "); append("$failed failed") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (failed > 0) {
                    val actions = LocalSongActions.current
                    TextButton(onClick = { actions.download(pending.filter { it.failed }.map { it.track }) }) { Text("Retry") }
                }
            }
        }
        Text(
            "${songCount(done.size)} on this phone · ${com.aurawave.player.playback.Downloads.formatSize(used)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SongShelf(songs = done, player = player, emptyTitle = "Nothing finished yet", emptyBody = "Songs appear here as they finish downloading.")
    }
}

@Composable
private fun AlbumShelf(graph: AppGraph) {
    val albums by graph.library.savedAlbums.collectAsStateWithLifecycle(initialValue = null)
    val actions = LocalSongActions.current
    val list = albums ?: return
    if (list.isEmpty()) {
        MessageBox(title = "No saved albums", body = "Open an album and tap the save button at the top to keep it here.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(list, key = { it.id }) { a -> AlbumTile(a) { actions.openAlbum(a) } }
    }
}

@Composable
private fun ArtistShelf(graph: AppGraph) {
    val artists by graph.library.followedArtists.collectAsStateWithLifecycle(initialValue = null)
    val actions = LocalSongActions.current
    val list = artists ?: return
    if (list.isEmpty()) {
        MessageBox(title = "No artists followed", body = "Open an artist and tap Follow to keep them here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(list, key = { it.id }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openArtist(ArtistRef(a.name, a.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(a.imageUrl, 56.dp, corner = 28.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PlaylistShelf(
    graph: AppGraph,
    player: PlayerConnection,
    playlists: List<PlaylistSummary>?,
    onOpenPlaylist: (Long) -> Unit,
    /** Open with the link box already showing. */
    askForLink: Boolean = false,
    onAsked: () -> Unit = {},
) {
    val actions = LocalSongActions.current
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(askForLink) }
    LaunchedEffect(Unit) { onAsked() }
    val scope = rememberCoroutineScope()
    val progress by graph.importer.progress.collectAsStateWithLifecycle()
    if (playlists == null) return

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Text("New playlist", style = MaterialTheme.typography.bodyLarge)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = progress !is PlaylistImporter.Progress.Working) { importing = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Import from a link", style = MaterialTheme.typography.bodyLarge)
                    Text("A song, album or playlist from Spotify or YouTube", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        (progress as? PlaylistImporter.Progress.Working)?.let { p ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if (p.total > 0) "${p.step}: ${p.done} of ${p.total}" else p.step, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "Playlists you make appear here. Songs can be added from any song's menu. " +
                        "Playlists from the old AuraWave app come in with Settings › Restore from a backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(playlists, key = { it.playlist.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenPlaylist(p.playlist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(p.coverUrl, 56.dp, corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        songCount(p.songCount) + sourceLabel(p.playlist.source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (importing) {
        LinkDialog(onDismiss = { importing = false }, onImport = { link -> importing = false; graph.importer.import(link) })
    }
    when (val p = progress) {
        is PlaylistImporter.Progress.Done -> AlertDialog(
            onDismissRequest = graph.importer::dismiss,
            containerColor = Palette.Dusk,
            title = { Text("Imported ${p.name}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(if (p.added == 0) "No new songs: the playlist was already up to date." else "${songCount(p.added)} added.")
                    if (p.unmatched.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("${p.unmatched.size} songs could not be found on YouTube Music:", fontWeight = FontWeight.SemiBold)
                        p.unmatched.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { graph.importer.dismiss(); onOpenPlaylist(p.playlistId) }) { Text("Open playlist") }
            },
            dismissButton = { TextButton(onClick = graph.importer::dismiss) { Text("Close") } },
        )
        is PlaylistImporter.Progress.Found -> AlertDialog(
            onDismissRequest = graph.importer::dismiss,
            containerColor = Palette.Dusk,
            title = { Text("Found the song") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(p.track.coverUrl, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p.track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { graph.importer.dismiss(); player.play(listOf(p.track)) }) { Text("Play") }
            },
            dismissButton = {
                TextButton(onClick = { graph.importer.dismiss(); actions.addToPlaylist(listOf(p.track)) }) { Text("Add to playlist") }
            },
        )
        is PlaylistImporter.Progress.Failed -> AlertDialog(
            onDismissRequest = graph.importer::dismiss,
            containerColor = Palette.Dusk,
            title = { Text("Couldn't import from that link") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = graph.importer::dismiss) { Text("Close") } },
        )
        else -> Unit
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
private fun LinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    LaunchedEffect(Unit) {
        // Offer what was just copied from Spotify or YouTube.
        val copied = runCatching { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
        if (copied.startsWith("http") && MusicLink.parse(copied) != null) link = copied
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Dusk,
        title = { Text("Import from a link") },
        text = {
            Column {
                Text(
                    "Paste the link from Share in Spotify, YouTube or YouTube Music: a song, an album or a playlist. " +
                        "It must be public. Spotify songs are matched to YouTube Music; Spotify shows at most the first 100 songs of a playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(link.trim()) }, enabled = link.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sourceLabel(source: String): String = when (source) {
    "spotify" -> " · from Spotify"
    "youtube" -> " · from YouTube"
    "ytm" -> " · synced with YouTube Music"
    else -> ""
}
