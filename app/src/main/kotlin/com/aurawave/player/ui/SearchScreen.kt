package com.aurawave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.SearchResult
import com.aurawave.innertube.Track
import com.aurawave.player.data.Catalog
import com.aurawave.player.data.Library
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchFilter(val label: String) {
    All("All"),
    Songs("Songs"),
    Videos("Videos"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
    Podcasts("Podcasts"),
}

/** Search text, suggestions and results. Kept by AppRoot so switching tabs does not lose them. */
class SearchState(private val catalog: Catalog, val library: Library, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var filter by mutableStateOf(SearchFilter.All)
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

    fun changeFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        if (submitted.isNotEmpty()) submit(submitted)
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
        val f = filter
        searchJob = scope.launch {
            try {
                result = run(q, f)
                library.addSearch(q)
                loading = false
            } catch (e: CancellationException) {
                throw e // a newer search took over; it owns the loading flag now
            } catch (e: Exception) {
                error = friendlyError(e)
                loading = false
            }
        }
    }

    private suspend fun run(q: String, f: SearchFilter): SearchResult = when (f) {
        SearchFilter.All -> coroutineScope {
            val albums = async { runCatching { catalog.searchAlbums(q) }.getOrDefault(emptyList()) }
            val playlists = async { runCatching { catalog.searchPlaylists(q) }.getOrDefault(emptyList()) }
            catalog.search(q).copy(albums = albums.await().take(10), playlists = playlists.await().take(10))
        }
        SearchFilter.Songs -> SearchResult(songs = catalog.searchSongsOnly(q))
        SearchFilter.Videos -> SearchResult(videos = catalog.searchVideos(q))
        SearchFilter.Albums -> SearchResult(albums = catalog.searchAlbums(q))
        SearchFilter.Artists -> SearchResult(artists = catalog.searchArtists(q))
        SearchFilter.Playlists -> SearchResult(playlists = catalog.searchPlaylists(q))
        SearchFilter.Podcasts -> SearchResult(podcasts = catalog.searchPodcasts(q))
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

private fun SearchResult.isEmpty() =
    songs.isEmpty() && videos.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && podcasts.isEmpty()

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
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            placeholder = { Text("Songs, artists, albums or a line of lyrics") },
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
        if (state.submitted.isNotEmpty() && !(typingNewQuery && state.suggestions.isNotEmpty())) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { state.changeFilter(f) },
                        label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Palette.OnAura,
                        ),
                    )
                }
            }
        }
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

            result == null -> SearchHome(state, onPick = { submit(it) })

            result.isEmpty() -> MessageBox(
                title = "Nothing found for “${state.submitted}”",
                body = "Check the spelling, or try the artist's name with the song title.",
            )

            else -> SearchResults(result, state.filter, currentId, player, onOpenArtist)
        }
    }
}

/** Before searching: recent searches and ways into YouTube Music's catalogue. */
@Composable
private fun SearchHome(state: SearchState, onPick: (String) -> Unit) {
    val recent by state.library.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = LocalSongActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { ShelfHeader("Browse") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BROWSE.forEach { (title, id, color) -> MoodTile(title, color) { actions.openBrowse(title, id, "") } }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = state.library::clearSearches) { Text("Clear all") }
                }
            }
            items(recent, key = { it }) { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = Palette.Faint)
                    Spacer(Modifier.width(16.dp))
                    Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { state.library.removeSearch(q) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove $q from recent searches", tint = Palette.Faint)
                    }
                }
            }
        }
    }
}

private val BROWSE = listOf(
    Triple("Explore", InnerTube.EXPLORE, 0xFFB388FFL),
    Triple("Charts", InnerTube.CHARTS, 0xFFFF8A65L),
    Triple("Moods & genres", InnerTube.MOODS_AND_GENRES, 0xFF4DD0E1L),
    Triple("New releases", InnerTube.NEW_RELEASES, 0xFFAED581L),
)

@Composable
private fun SearchResults(result: SearchResult, filter: SearchFilter, currentId: String?, player: PlayerConnection, onOpenArtist: (ArtistRef) -> Unit) {
    val actions = LocalSongActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (result.artists.isNotEmpty()) {
            if (filter == SearchFilter.Artists) {
                items(result.artists, key = { "a-${it.id}" }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenArtist(ArtistRef(a.name, a.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(a.imageUrl, 56.dp, corner = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.audience.isNotEmpty()) Text(a.audience, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        if (result.songs.isNotEmpty()) {
            if (filter == SearchFilter.All) item { SectionTitle("Songs") }
            items(result.songs, key = { "s-${it.videoId}" }) { track -> ResultRow(track, currentId, player) }
        }
        if (filter == SearchFilter.All) {
            if (result.albums.isNotEmpty()) {
                item { ShelfHeader("Albums") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(result.albums, key = { it.id }) { a -> AlbumTile(a) { actions.openAlbum(a) } }
                    }
                }
            }
            if (result.playlists.isNotEmpty()) {
                item { ShelfHeader("Playlists") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(result.playlists, key = { it.id }) { p -> PlaylistTile(p) { actions.openOnlinePlaylist(p) } }
                    }
                }
            }
        } else {
            items(result.albums, key = { "al-${it.id}" }) { a ->
                LinkRow(a.coverUrl, a.title, listOf(a.type, a.artist, a.year).filter { it.isNotEmpty() }.joinToString(" · ")) { actions.openAlbum(a) }
            }
            items(result.playlists, key = { "pl-${it.id}" }) { p ->
                LinkRow(p.coverUrl, p.title, listOf(p.author, p.countText).filter { it.isNotEmpty() }.joinToString(" · ")) { actions.openOnlinePlaylist(p) }
            }
            items(result.podcasts, key = { "pod-${it.id}" }) { p ->
                LinkRow(p.coverUrl, p.title, p.author) { actions.openPodcast(p) }
            }
        }
        if (result.videos.isNotEmpty()) {
            if (filter == SearchFilter.All) item { SectionTitle("Music videos") }
            items(result.videos, key = { "v-${it.videoId}" }) { track -> ResultRow(track, currentId, player) }
        }
    }
}

/** A row that opens an album or playlist. */
@Composable
private fun LinkRow(coverUrl: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(coverUrl, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
