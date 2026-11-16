package com.aurawave.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.Track
import com.aurawave.innertube.InnerTube
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

@Composable
fun PlaylistScreen(graph: AppGraph, player: PlayerConnection, id: Long, onBack: () -> Unit) {
    val library = graph.library
    val playlist by remember(id) { library.playlist(id) }.collectAsStateWithLifecycle(initialValue = null)
    val songs by remember(id) { library.playlistSongs(id) }.collectAsStateWithLifecycle(initialValue = null)
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    val actions = LocalSongActions.current
    val scope = rememberCoroutineScope()

    // While reordering, the screen works on its own copy and saves it when a song is dropped.
    val order = remember { mutableStateListOf<Track>() }
    LaunchedEffect(songs, reordering) {
        if (!reordering || order.isEmpty()) {
            order.clear()
            order.addAll(songs.orEmpty())
        }
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        firstIndex = 1,
        onMove = { from, to -> order.add(to, order.removeAt(from)) },
        onDrop = { library.reorderPlaylist(id, order.map { it.videoId }) },
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if (reordering) {
                TextButton(onClick = { reordering = false }) { Text("Done") }
            } else if (playlist != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        if ((songs?.size ?: 0) > 1) {
                            DropdownMenuItem(text = { Text("Change order") }, onClick = { menuOpen = false; reordering = true })
                        }
                        if (!songs.isNullOrEmpty()) {
                            DropdownMenuItem(text = { Text("Add all to queue") }, onClick = {
                                menuOpen = false
                                player.addToQueue(songs.orEmpty())
                                actions.say("Added ${songCount(songs.orEmpty().size)} to the queue")
                            })
                        }
                        if (graph.account.signedIn && playlist?.source != com.aurawave.player.data.Account.SOURCE) {
                            DropdownMenuItem(text = { Text("Copy to YouTube Music") }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    val problem = graph.account.copyToYouTubeMusic(id)
                                    actions.say(problem ?: "Copied to YouTube Music. Songs you add here are added there too.")
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; deleting = true })
                    }
                }
            }
        }

        val p = playlist
        val list = songs
        when {
            p == null || list == null -> Unit // loading, or just deleted
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") {
                    CollectionHeader(
                        title = p.name,
                        subtitle = buildString {
                            append(songCount(list.size))
                            val total = list.sumOf { it.durationSec }
                            if (total > 0) append(" · ").append(formatDuration(total))
                        },
                        coverUrl = list.firstOrNull()?.coverUrl,
                        onPlay = if (list.isEmpty()) null else ({ player.play(list) }),
                        onShuffle = if (list.size < 2) null else ({ player.play(list.shuffled()) }),
                        extra = { DownloadAllButton(list, p.name) },
                    )
                    if (reordering) {
                        Text(
                            "Drag the handles to change the order.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("This playlist is empty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Add songs from any song's menu, under “Add to playlist”.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val shown = if (reordering) order else list
                itemsIndexed(shown, key = { _, t -> t.videoId }) { i, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.videoId == player.current?.mediaId,
                        onClick = { if (!reordering) player.play(list, i) },
                        onPlayNext = { player.playNext(track) },
                        onAddToQueue = { player.addToQueue(track) },
                        extraActions = listOf("Remove from this playlist" to { library.removeFromPlaylist(id, track.videoId) }),
                        trailing = if (reordering) {
                            {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Drag to move ${track.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.dragHandle(reorder, track.videoId).padding(12.dp),
                                )
                            }
                        } else null,
                        modifier = reorderableItem(reorder, track.videoId),
                    )
                }
            }
        }
    }

    val p = playlist
    if (renaming && p != null) {
        NameDialog(
            title = "Rename playlist",
            initial = p.name,
            confirmLabel = "Rename",
            onDismiss = { renaming = false },
            onConfirm = { library.renamePlaylist(id, it); renaming = false },
        )
    }
    if (deleting && p != null) {
        ConfirmDialog(
            title = "Delete “${p.name}”?",
            body = "The playlist is removed from this phone. The songs stay in your liked songs and history.",
            confirmLabel = "Delete playlist",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                library.deletePlaylist(id)
                actions.say("Deleted ${p.name}")
                onBack()
            },
        )
    }
}

/** The top of a playlist, album or similar page: cover, name, details and the play buttons. */
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    coverUrl: String?,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    description: String = "",
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .coverGlow(InnerTube.bigCover(coverUrl, coverPx(120.dp)))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(coverUrl, 120.dp, corner = 10.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onPlay != null || onShuffle != null || extra != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onPlay != null) {
                    Button(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
                if (onShuffle != null) {
                    OutlinedButton(onClick = onShuffle) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
                extra?.invoke()
            }
        }
    }
}

/** "1 hr 12 min", "38 min". */
fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "${maxOf(m, 1)} min"
    }
}
