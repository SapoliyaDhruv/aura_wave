package com.aurawave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.SearchResult
import com.aurawave.innertube.Track
import com.aurawave.player.data.Catalog
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Search tab is showing. Kept above the screen so it survives switching tabs. */
@Stable
class SearchState(private val catalog: Catalog, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var result by mutableStateOf<SearchResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The query the current result (or failure) belongs to. */
    var submitted by mutableStateOf("")
        private set

    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(250) // wait for a pause in typing
            suggestions = runCatching { catalog.suggestions(text) }.getOrDefault(emptyList())
        }
    }

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        submitted = q
        suggestJob?.cancel()
        suggestions = emptyList()
        searchJob?.cancel()
        loading = true
        error = null
        result = null
        searchJob = scope.launch {
            try {
                result = catalog.search(q)
                loading = false
            } catch (e: CancellationException) {
                throw e // a newer search took over; it owns the loading flag now
            } catch (e: Exception) {
                error = friendlyError(e)
                loading = false
            }
        }
    }

    fun clear() {
        suggestJob?.cancel()
        searchJob?.cancel()
        query = ""
        submitted = ""
        suggestions = emptyList()
        result = null
        error = null
        loading = false
    }
}

@Composable
fun SearchScreen(state: SearchState, player: PlayerConnection, onOpenArtist: (ArtistRef) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = { text: String ->
        keyboard?.hide()
        focus.clearFocus()
        state.submit(text)
    }
    val currentId = player.current?.mediaId

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = state::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Songs, artists or a line of lyrics") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = state::clear) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.Raised,
                unfocusedContainerColor = Palette.Raised,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = Palette.Haze,
                unfocusedLeadingIconColor = Palette.Haze,
                focusedPlaceholderColor = Palette.Faint,
                unfocusedPlaceholderColor = Palette.Faint,
            ),
        )

        val result = state.result
        // Typing something new replaces the old results with suggestions for the new text.
        val typingNewQuery = state.query.trim() != state.submitted
        when {
            typingNewQuery && state.suggestions.isNotEmpty() ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.suggestions) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Palette.Faint)
                            Spacer(Modifier.width(16.dp))
                            Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

            state.loading -> LoadingBox()

            state.error != null -> MessageBox(
                title = "Search didn't go through",
                body = state.error.orEmpty(),
                action = "Try again",
                onAction = { submit(state.submitted) },
            )

            result == null -> MessageBox(
                title = "Find any song",
                body = "Type a title, an artist, or any line you remember from the lyrics.",
            )

            result.songs.isEmpty() && result.videos.isEmpty() && result.artists.isEmpty() -> MessageBox(
                title = "Nothing found for \"${state.submitted}\"",
                body = "Check the spelling, or try the artist's name with the song title.",
            )

            else -> SearchResults(result, currentId, player, onOpenArtist)
        }
    }
}

@Composable
private fun SearchResults(result: SearchResult, currentId: String?, player: PlayerConnection, onOpenArtist: (ArtistRef) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (result.artists.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(result.artists, key = { it.id }) { a ->
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(Palette.Raised)
                                .clickable(onClickLabel = "Open ${a.name}") { onOpenArtist(ArtistRef(a.name, a.id)) }
                                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(a.imageUrl, 36.dp, corner = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (result.songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            items(result.songs, key = { "s-${it.videoId}" }) { track -> ResultRow(track, currentId, player) }
        }
        if (result.videos.isNotEmpty()) {
            item { SectionTitle("Music videos") }
            items(result.videos, key = { "v-${it.videoId}" }) { track -> ResultRow(track, currentId, player) }
        }
    }
}

/** Tapping a search result plays that one song; similar songs follow automatically. */
@Composable
private fun ResultRow(track: Track, currentId: String?, player: PlayerConnection) {
    TrackRow(
        track = track,
        isCurrent = track.videoId == currentId,
        onClick = { player.play(listOf(track)) },
        onPlayNext = { player.playNext(track) },
        onAddToQueue = { player.addToQueue(track) },
    )
}
