package com.aurawave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.PlaylistRef
import com.aurawave.player.data.Mix
import com.aurawave.innertube.Track
import com.aurawave.player.data.Library
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What any song row or player screen can ask the app to do. Provided by AppRoot. */
class SongActions(
    val addToPlaylist: (List<Track>) -> Unit,
    val openArtist: (ArtistRef) -> Unit,
    /** A playlist in the listener's library. */
    val openPlaylist: (Long) -> Unit,
    val openAlbum: (AlbumRef) -> Unit,
    /** A YouTube or YouTube Music playlist, by id. */
    val openOnlinePlaylist: (PlaylistRef) -> Unit,
    /** A page of rows from YouTube Music: Explore, charts, a mood. */
    val openBrowse: (title: String, browseId: String, params: String) -> Unit,
    /** An artist's full list of albums or singles. */
    val openReleases: (title: String, browseId: String, params: String) -> Unit,
    val openMix: (Mix) -> Unit,
    val openPodcast: (com.aurawave.innertube.PodcastRef) -> Unit,
    val download: (List<Track>) -> Unit,
    val removeDownload: (String) -> Unit,
    val say: (String) -> Unit,
)

/** Downloads by video id, so any row can show whether its song is on the phone. */
val LocalDownloads = androidx.compose.runtime.compositionLocalOf<Map<String, com.aurawave.player.playback.SongDownload>> { emptyMap() }

val LocalSongActions = staticCompositionLocalOf<SongActions> { error("SongActions are provided by AppRoot") }

/** The artist to open for a song: its first credited artist, found by name when YouTube gave no id. */
fun Track.leadArtist(): ArtistRef? =
    artists.firstOrNull() ?: artist.split(',', '&').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "Unknown artist" }?.let { ArtistRef(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(library: Library, tracks: List<Track>, onDismiss: () -> Unit, say: (String) -> Unit) {
    val playlists by library.playlists.collectAsStateWithLifecycle(initialValue = null)
    val single = tracks.singleOrNull()
    val containing by remember(single?.videoId) {
        single?.let { library.playlistsContaining(it.videoId) } ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val what = if (single != null) "Song" else "${tracks.size} songs"

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Dusk) {
        Text(
            if (single != null) "Add to playlist" else "Add ${tracks.size} songs to a playlist",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SheetRow(onClick = { naming = true }) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("New playlist", style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(playlists.orEmpty(), key = { it.playlist.id }) { p ->
                val has = p.playlist.id in containing
                SheetRow(onClick = {
                    scope.launch {
                        val added = library.addToPlaylist(p.playlist.id, tracks)
                        say(
                            when {
                                added == 0 && single != null -> "Already in ${p.playlist.name}"
                                added == 0 -> "All of these are already in ${p.playlist.name}"
                                single != null -> "Added to ${p.playlist.name}"
                                else -> "Added $added songs to ${p.playlist.name}"
                            },
                        )
                        onDismiss()
                    }
                }) {
                    Artwork(p.coverUrl, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songCount(p.songCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (has) Icon(Icons.Rounded.Check, contentDescription = "$what is in this playlist", tint = MaterialTheme.colorScheme.primary)
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
                scope.launch {
                    library.createPlaylist(name, tracks)
                    say("Created ${name.trim().ifEmpty { "New playlist" }}")
                    onDismiss()
                }
            },
        )
    }
}

@Composable
private fun SheetRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"

/** Asks for a name (new playlist, rename). The field is focused with its text selected. */
@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Dusk,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Playlist name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms something that cannot be undone. The button names the action. */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Dusk,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
