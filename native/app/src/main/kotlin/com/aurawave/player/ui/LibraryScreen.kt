package com.aurawave.player.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

private enum class Shelf(val label: String) {
    Liked("Liked songs"),
    History("History"),
}

@Composable
fun LibraryScreen(graph: AppGraph, player: PlayerConnection) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.Liked) }
    val liked by graph.library.liked.collectAsStateWithLifecycle(initialValue = null)
    val history by graph.library.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val currentId = player.current?.mediaId

    Column(Modifier.fillMaxSize()) {
        Text(
            "Your library",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Shelf.entries.forEach { s ->
                FilterChip(
                    selected = shelf == s,
                    onClick = { shelf = s },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Palette.OnAura,
                    ),
                )
            }
        }

        val songs = if (shelf == Shelf.Liked) liked else history
        when {
            songs == null -> Unit // still reading the library
            songs.isEmpty() && shelf == Shelf.Liked -> MessageBox(
                title = "No liked songs yet",
                body = "Tap the heart on a song you love and it is kept here.",
            )
            songs.isEmpty() -> MessageBox(
                title = "Nothing played yet",
                body = "Songs you listen to appear here, most recent first.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (songs.size == 1) "1 song" else "${songs.size} songs",
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
}
