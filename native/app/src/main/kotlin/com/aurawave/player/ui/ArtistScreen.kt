package com.aurawave.player.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.InnerTube
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

@Composable
fun ArtistScreen(graph: AppGraph, player: PlayerConnection, artist: ArtistRef, onBack: () -> Unit) {
    val loader = rememberLoader(artist, "No songs were found for ${artist.name}.") {
        graph.catalog.artist(artist.id, artist.name)?.takeIf { it.songs.isNotEmpty() || it.albums.isNotEmpty() }
    }
    val following by graph.library.followedIds.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    var allSongs by remember(artist) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(onBack)
        LoadContent(loader, "Couldn't open ${artist.name}") { page ->
            val isFollowing = page.id in following
            val currentId = player.current?.mediaId
            val songs = if (allSongs) page.songs else page.songs.take(TOP_SONGS)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .coverGlow(InnerTube.bigCover(page.imageUrl, coverPx(104.dp)))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(page.imageUrl, 104.dp, corner = 52.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (page.audience.isNotEmpty()) {
                                Text(page.audience, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page.songs.isNotEmpty()) {
                            Button(onClick = { player.play(page.songs) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play")
                            }
                            OutlinedButton(onClick = { player.play(page.songs.shuffled()) }) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle")
                            }
                        }
                        OutlinedButton(onClick = {
                            graph.library.setFollowing(page.id, page.name, page.imageUrl, !isFollowing)
                            actions.say(if (isFollowing) "Unfollowed ${page.name}" else "Following ${page.name}")
                        }) { Text(if (isFollowing) "Following" else "Follow") }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionTitle("Top songs") }
                    itemsIndexed(songs, key = { _, t -> t.videoId }) { i, track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.videoId == currentId,
                            onClick = { player.play(page.songs, i) },
                            onPlayNext = { player.playNext(track) },
                            onAddToQueue = { player.addToQueue(track) },
                        )
                    }
                    if (!allSongs && page.songs.size > TOP_SONGS) {
                        item {
                            TextButton(onClick = { allSongs = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Show all ${page.songs.size} songs")
                            }
                        }
                    }
                }
                if (page.albums.isNotEmpty()) {
                    item {
                        AlbumRow("Albums", page.albums, onMore = page.allAlbums?.let { (id, params) -> { actions.openReleases("${page.name}: albums", id, params) } })
                    }
                }
                if (page.singles.isNotEmpty()) {
                    item {
                        AlbumRow("Singles and EPs", page.singles, onMore = page.allSingles?.let { (id, params) -> { actions.openReleases("${page.name}: singles", id, params) } })
                    }
                }
                if (page.playlists.isNotEmpty()) {
                    item { ShelfHeader("Playlists") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(page.playlists, key = { it.id }) { p -> PlaylistTile(p) { actions.openOnlinePlaylist(p) } }
                        }
                    }
                }
                if (page.similar.isNotEmpty()) {
                    item { ShelfHeader("Fans also like") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(page.similar, key = { it.id }) { a -> ArtistTile(a) { actions.openArtist(ArtistRef(a.name, a.id)) } }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun AlbumRow(title: String, albums: List<AlbumRef>, onMore: (() -> Unit)?) {
    val actions = LocalSongActions.current
    ShelfHeader(title, onMore)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { a -> AlbumTile(a.copy(artist = "")) { actions.openAlbum(a) } }
    }
}

private const val TOP_SONGS = 10
