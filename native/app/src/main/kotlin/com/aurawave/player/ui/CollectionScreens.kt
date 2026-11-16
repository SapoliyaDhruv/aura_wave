package com.aurawave.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import com.aurawave.innertube.Track
import com.aurawave.player.data.Recommender
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.BrowseItem
import com.aurawave.innertube.PlaylistRef
import com.aurawave.player.AppGraph
import com.aurawave.player.data.Mix
import com.aurawave.player.data.PlaylistImporter
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.launch

/** Back arrow, an optional title, and actions on the right. */
@Composable
fun BackBar(onBack: () -> Unit, title: String = "", actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun AlbumScreen(graph: AppGraph, player: PlayerConnection, ref: AlbumRef, onBack: () -> Unit) {
    val loader = rememberLoader(ref.id, "This album could not be found.") { graph.catalog.album(ref.id) }
    val saved by graph.library.savedAlbumIds.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    val isSaved = ref.id in saved

    Column(Modifier.fillMaxSize()) {
        BackBar(onBack) {
            val album = (loader.state as? Load.Ready)?.value?.album ?: ref
            IconButton(onClick = {
                graph.library.setAlbumSaved(album, !isSaved)
                actions.say(if (isSaved) "Removed from your albums" else "Saved to your albums")
            }) {
                Icon(
                    if (isSaved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                    contentDescription = if (isSaved) "Remove from your albums" else "Save to your albums",
                    tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        LoadContent(loader, "Couldn't open ${ref.title}") { page ->
            val tracks = page.tracks
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    CollectionHeader(
                        title = page.album.title,
                        subtitle = listOf(page.album.type, page.album.year, page.info).filter { it.isNotEmpty() }.joinToString(" · "),
                        coverUrl = page.album.coverUrl,
                        onPlay = if (tracks.isEmpty()) null else ({ player.play(tracks) }),
                        onShuffle = if (tracks.size < 2) null else ({ player.play(tracks.shuffled()) }),
                        description = page.description,
                        extra = { DownloadAllButton(tracks, page.album.title) },
                    )
                    if (page.artists.isNotEmpty()) {
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            page.artists.take(3).forEach { a ->
                                TextButton(onClick = { actions.openArtist(a) }) { Text(a.name) }
                            }
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, t -> t.videoId }) { i, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.videoId == player.current?.mediaId,
                        onClick = { player.play(tracks, i) },
                        onPlayNext = { player.playNext(track) },
                        onAddToQueue = { player.addToQueue(track) },
                        subtitle = "${i + 1}. ${track.artist}" + if (track.durationSec > 0) " · ${formatTime(track.durationSec * 1000L)}" else "",
                    )
                }
                page.more.forEach { shelf -> item { ShelfRow(shelf, player) } }
            }
        }
    }
}

@Composable
fun OnlinePlaylistScreen(graph: AppGraph, player: PlayerConnection, ref: PlaylistRef, onBack: () -> Unit) {
    val loader = rememberLoader(ref.id, "This playlist could not be found or is private.") { graph.catalog.playlist(ref.id) }
    val progress by graph.importer.progress.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    var savedId by remember(ref.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(ref.id) { savedId = graph.library.importedPlaylist("youtube", ref.id)?.id }
    LaunchedEffect(progress) {
        val p = progress
        if (p is PlaylistImporter.Progress.Done && savedId == null) {
            savedId = graph.library.importedPlaylist("youtube", ref.id)?.id
            if (savedId != null) {
                actions.say("Saved ${p.name} to your playlists")
                graph.importer.dismiss()
            }
        }
    }
    val saving = progress is PlaylistImporter.Progress.Working

    Column(Modifier.fillMaxSize()) {
        BackBar(onBack) {
            IconButton(
                enabled = !saving,
                onClick = {
                    val id = savedId
                    if (id != null) actions.openPlaylist(id) else graph.importer.saveYouTube(ref.id)
                },
            ) {
                Icon(
                    if (savedId != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (savedId != null) "Open your saved copy" else "Save to your playlists",
                    tint = if (savedId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        LoadContent(loader, "Couldn't open ${ref.title}") { page ->
            val tracks = page.tracks
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    CollectionHeader(
                        title = page.playlist.title,
                        subtitle = listOf(page.playlist.author, page.info).filter { it.isNotEmpty() }.joinToString(" · "),
                        coverUrl = page.playlist.coverUrl.ifEmpty { ref.coverUrl },
                        onPlay = if (tracks.isEmpty()) null else ({ player.play(tracks) }),
                        onShuffle = if (tracks.size < 2) null else ({ player.play(tracks.shuffled()) }),
                        description = page.description,
                        extra = { DownloadAllButton(tracks, page.playlist.title) },
                    )
                }
                itemsIndexed(tracks, key = { i, t -> "$i:${t.videoId}" }) { i, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.videoId == player.current?.mediaId,
                        onClick = { player.play(tracks, i) },
                        onPlayNext = { player.playNext(track) },
                        onAddToQueue = { player.addToQueue(track) },
                    )
                }
            }
        }
    }
}

/** A YouTube Music page made of rows: Explore, charts, moods and genres, one mood, new releases. */
@Composable
fun BrowseScreen(graph: AppGraph, player: PlayerConnection, title: String, browseId: String, params: String, onBack: () -> Unit) {
    val loader = rememberLoader("$browseId:$params", "Nothing to show here right now.") {
        graph.catalog.shelves(browseId, params).ifEmpty { null }
    }
    val actions = LocalSongActions.current
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, title)
        LoadContent(loader, "Couldn't open $title") { shelves ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                shelves.forEach { shelf ->
                    val moods = shelf.items.filterIsInstance<BrowseItem.Mood>()
                    if (moods.size == shelf.items.size) {
                        // Moods and genres read better as a grid than as a long row.
                        item { ShelfHeader(shelf.title) }
                        item {
                            FlowRow(
                                Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                moods.forEach { m ->
                                    MoodTile(m.category.title, m.category.color) {
                                        actions.openBrowse(m.category.title, m.category.browseId, m.category.params)
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            ShelfRow(
                                shelf,
                                player,
                                onMore = shelf.moreBrowseId.takeIf { it.isNotEmpty() && it != browseId }?.let { id ->
                                    {
                                        if (id.startsWith("VL")) {
                                            actions.openOnlinePlaylist(PlaylistRef(id.removePrefix("VL"), shelf.title))
                                        } else {
                                            actions.openBrowse(shelf.title, id, shelf.moreParams)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** All of an artist's albums, or all singles. */
@Composable
fun ReleasesScreen(graph: AppGraph, title: String, browseId: String, params: String, onBack: () -> Unit) {
    val loader = rememberLoader("$browseId:$params", "No releases found.") {
        graph.catalog.artistReleases(browseId, params).ifEmpty { null }
    }
    val actions = LocalSongActions.current
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, title)
        LoadContent(loader, "Couldn't open $title") { albums ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }) { album -> AlbumTile(album) { actions.openAlbum(album) } }
            }
        }
    }
}

/** A recommended mix: each song shows why it was picked. */
@Composable
fun MixScreen(graph: AppGraph, player: PlayerConnection, mix: Mix, onBack: () -> Unit) {
    val actions = LocalSongActions.current
    val scope = rememberCoroutineScope()
    val tracks = remember(mix) { mix.picks.map { it.track } }
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                CollectionHeader(
                    title = mix.name,
                    subtitle = "${songCount(tracks.size)} · ${mix.subtitle}",
                    coverUrl = tracks.firstOrNull()?.coverUrl,
                    onPlay = { player.play(tracks) },
                    onShuffle = { player.play(tracks.shuffled()) },
                    extra = {
                        TextButton(onClick = {
                            scope.launch {
                                val id = graph.library.createPlaylist(mix.name, tracks)
                                actions.say("Saved as a playlist")
                                actions.openPlaylist(id)
                            }
                        }) { Text("Save as playlist") }
                    },
                )
            }
            itemsIndexed(mix.picks, key = { _, p -> p.track.videoId }) { i, pick ->
                TrackRow(
                    track = pick.track,
                    isCurrent = pick.track.videoId == player.current?.mediaId,
                    onClick = { player.play(tracks, i) },
                    onPlayNext = { player.playNext(pick.track) },
                    onAddToQueue = { player.addToQueue(pick.track) },
                    subtitle = "${pick.track.artist} · ${pick.reason}",
                    extraActions = notInterested(graph, pick.track),
                )
            }
        }
    }
}

/** Menu entries that keep a recommended song, or its artist, out of future recommendations. */
fun notInterested(graph: AppGraph, track: Track): List<Pair<String, () -> Unit>> {
    val artist = Recommender.leadArtist(track)
    return listOf(
        "Not interested" to { graph.library.hideSong(track) },
        "Don't recommend $artist" to { graph.library.hideArtist(artist) },
    )
}
