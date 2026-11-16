package com.aurawave.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}

@Composable
fun HomeScreen(graph: AppGraph, player: PlayerConnection, onOpenSearch: () -> Unit) {
    val loaded by graph.library.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val recent = loaded ?: return // still reading the library: draw nothing rather than flash the empty state
    if (recent.isEmpty()) {
        MessageBox(
            title = "Start with a song you love",
            body = "Search for a song, an artist, or a line of the lyrics. This page fills in as you listen.",
            action = "Search music",
            onAction = onOpenSearch,
        )
        return
    }

    // Suggestions follow the song that was most recent when this screen opened, so the
    // list does not reshuffle under the listener's thumb every time a song changes.
    val seed = remember { recent.first() }
    var related by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(seed.videoId) {
        related = runCatching { graph.catalog.radio(seed.videoId) }.getOrDefault(emptyList()).take(20)
    }
    val currentId = player.current?.mediaId

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                greeting(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
        }
        item { SectionTitle("Recently played") }
        item {
            val shown = recent.take(12)
            val rowState = rememberLazyListState()
            // A newly played song joins at the front: bring the row back to the start to show it.
            LaunchedEffect(shown.firstOrNull()?.videoId) { rowState.scrollToItem(0) }
            LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(shown, key = { _, t -> t.videoId }) { i, track ->
                    Column(Modifier.width(128.dp).clickable(onClickLabel = "Play ${track.title}") { player.play(shown, i) }) {
                        Artwork(track.coverUrl, 128.dp, corner = 10.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(shortTitle(track.title), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        val list = related
        item { SectionTitle("Because you played ${shortTitle(seed.title)}") }
        when {
            list == null -> item { LoadingBox() }
            list.isEmpty() -> item {
                Text(
                    "Couldn't load suggestions. They'll be back when you're online.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            else -> items(list, key = { it.videoId }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = track.videoId == currentId,
                    onClick = { player.play(list, list.indexOf(track)) },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }
    }
}
