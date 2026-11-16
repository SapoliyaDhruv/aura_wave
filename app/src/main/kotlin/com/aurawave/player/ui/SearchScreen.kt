package com.aurawave.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

    /** Set when something outside the screen (Home's "Search music", the Search tab pressed twice) wants the keyboard up. */
    var focusWanted by mutableStateOf(false)
        private set

    fun askForFocus() {
        focusWanted = true
    }

    fun focusGiven() {
        focusWanted = false
    }

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_AFTER_MS) // wait for a pause in typing
            val suggested = try {
                catalog.suggestions(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            suggestions = suggested

            // Still nothing more typed: show results for the words so far, without waiting for
            // Search to be pressed. A half-typed word that finds nothing changes nothing.
            delay(RESULTS_AFTER_MS)
            val q = text.trim()
            if (q.length < 2 || q == submitted) return@launch
            val found = try {
                run(q, filter)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (found.isEmpty()) return@launch
            // The suggestions stay, above the results, so no row changes under a finger reaching for one.
            searchJob?.cancel()
            submitted = q
            result = found
            error = null
            loading = false
        }
    }

    fun changeFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        if (submitted.isNotEmpty()) submit(submitted, again = true)
    }

    /** [again] runs the search even when its results are on screen (the filter changed). */
    fun submit(text: String = query, again: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        suggestJob?.cancel()
        suggestions = emptyList()
        if (!again && q == submitted && result != null && !loading) {
            library.addSearch(q) // already on screen from typing: pressing Search just files it under recent searches
            return
        }
        submitted = q
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

private const val SUGGEST_AFTER_MS = 200L
private const val RESULTS_AFTER_MS = 450L

/** Few enough that results arriving underneath them can be seen above the keyboard. */
private const val SUGGESTIONS_SHOWN = 5

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

    // Asked for from outside: put the cursor in the field and bring the keyboard up.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.focusWanted) {
        if (!state.focusWanted) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        state.focusGiven()
    }

    // The keyboard covers the bottom bar; the lists end above it, so the last rows can be reached.
    Column(Modifier.fillMaxSize().imePadding()) {
        SearchField(
            query = state.query,
            onQueryChange = state::onQueryChange,
            onClear = {
                state.clear()
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
            onSearch = { submit(state.query) },
            focusRequester = focusRequester,
        )

        val result = state.result
        // Typing something new replaces the old results with suggestions for the new text.
        val typingNewQuery = state.query.trim() != state.submitted
        // Not while suggestions are up: the chips arriving above them would push every row down.
        if (state.submitted.isNotEmpty() && state.suggestions.isEmpty()) {
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
                    items(state.suggestions.take(SUGGESTIONS_SHOWN)) { s -> SuggestionRow(s) { submit(s) } }
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

            else -> SearchResults(result, state.filter, currentId, player, onOpenArtist, state.suggestions.take(SUGGESTIONS_SHOWN), onSuggestion = { submit(it) })
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Palette.Faint)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The search field. It answers a touch: the outline and the magnifier take the song's
 * colour while it has the cursor, and the clear button fades in with the first letter.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAura.current.accent
    val outline by animateColorAsState(if (focused) accent.copy(alpha = 0.9f) else Palette.Line, tween(180), label = "searchOutline")
    val fill by animateColorAsState(if (focused) Palette.Dusk else Palette.Raised, tween(180), label = "searchFill")
    val shape = RoundedCornerShape(28.dp)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
            .border(1.5.dp, outline, shape)
            .focusRequester(focusRequester),
        placeholder = { Text("Songs, artists, albums or lyrics", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = if (focused) accent else Palette.Haze) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(tween(120)) + scaleIn(tween(160)), exit = fadeOut(tween(100)) + scaleOut(tween(120))) {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = shape,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill,
            unfocusedContainerColor = fill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = accent,
            focusedPlaceholderColor = Palette.Faint,
            unfocusedPlaceholderColor = Palette.Faint,
        ),
    )
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
private fun SearchResults(
    result: SearchResult,
    filter: SearchFilter,
    currentId: String?,
    player: PlayerConnection,
    onOpenArtist: (ArtistRef) -> Unit,
    /** Shown first while the results came from typing, in the places they already had on screen. */
    suggestions: List<String> = emptyList(),
    onSuggestion: (String) -> Unit = {},
) {
    val actions = LocalSongActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(suggestions, key = { "suggest:$it" }) { s -> SuggestionRow(s) { onSuggestion(s) } }
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
