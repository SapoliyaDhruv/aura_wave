package com.aurawave.player.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurawave.innertube.ArtistPage
import com.aurawave.innertube.ArtistRef
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.CancellationException

private sealed interface ArtistLoad {
    data object Loading : ArtistLoad
    data class Ready(val page: ArtistPage) : ArtistLoad
    data class Failed(val message: String) : ArtistLoad
}

@Composable
fun ArtistScreen(graph: AppGraph, player: PlayerConnection, artist: ArtistRef, onBack: () -> Unit) {
    var load by remember(artist) { mutableStateOf<ArtistLoad>(ArtistLoad.Loading) }
    var attempt by remember(artist) { mutableIntStateOf(0) }
    LaunchedEffect(artist, attempt) {
        load = ArtistLoad.Loading
        load = try {
            val page = graph.catalog.artist(artist.id, artist.name)
            if (page == null || page.songs.isEmpty()) ArtistLoad.Failed("No songs were found for ${artist.name}.") else ArtistLoad.Ready(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArtistLoad.Failed(friendlyError(e))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        }
        when (val state = load) {
            ArtistLoad.Loading -> LoadingBox()
            is ArtistLoad.Failed -> MessageBox(
                title = "Couldn't open ${artist.name}",
                body = state.message,
                action = "Try again",
                onAction = { attempt++ },
            )
            is ArtistLoad.Ready -> {
                val page = state.page
                val currentId = player.current?.mediaId
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(page.imageUrl, 96.dp, corner = 48.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    page.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (page.audience.isNotEmpty()) {
                                    Text(page.audience, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { player.play(page.songs, 0) }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play")
                                }
                            }
                        }
                    }
                    item { SectionTitle("Songs") }
                    itemsIndexed(page.songs, key = { _, t -> t.videoId }) { i, track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.videoId == currentId,
                            onClick = { player.play(page.songs, i) },
                            onPlayNext = { player.playNext(track) },
                            onAddToQueue = { player.addToQueue(track) },
                        )
                    }
                }
            }
        }
    }
}
