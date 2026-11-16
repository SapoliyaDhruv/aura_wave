package com.aurawave.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.PlaylistRef
import com.aurawave.innertube.PodcastRef
import com.aurawave.player.data.Mix
import com.aurawave.innertube.Track
import kotlinx.coroutines.launch
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Search("Search", Icons.Rounded.Search),
    Library("Library", Icons.Rounded.LibraryMusic),
    Podcasts("Podcasts", Icons.Rounded.Podcasts),
}

/** Pages opened on top of a tab. Back closes the newest one. */
private sealed interface Screen {
    data class Artist(val ref: ArtistRef) : Screen
    data class Playlist(val id: Long) : Screen
    data class Album(val ref: AlbumRef) : Screen
    data class OnlinePlaylist(val ref: PlaylistRef) : Screen
    data class Browse(val title: String, val browseId: String, val params: String) : Screen
    data class Releases(val title: String, val browseId: String, val params: String) : Screen
    data class MixPage(val mix: Mix) : Screen
    data class Podcast(val ref: PodcastRef) : Screen
    data object Settings : Screen
    data object Login : Screen
    data object Stats : Screen
}

private class Page(val id: Int, val screen: Screen) {
    /** Names this page's saved state; never reused, even when another page later takes its place. */
    val key: String get() = "page:$id"
}

/** The pages open on top of the current tab, newest last. */
private class PageStack {
    val pages = mutableStateListOf<Page>()
    private var nextId = 0

    val top: Page? get() = pages.lastOrNull()
    val isEmpty: Boolean get() = pages.isEmpty()

    fun open(screen: Screen) {
        pages += Page(nextId++, screen)
    }

    /** Closes the newest page and returns it, or null when none is open. */
    fun close(): Page? = pages.removeLastOrNull()

    fun closeAll(): List<Page> = pages.toList().also { pages.clear() }
}

/** What fills the area above the bottom bar: a tab, or the newest page opened on top of it. */
private data class Shown(val key: String, val screen: Screen?, val tab: Tab, val depth: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(graph: AppGraph, player: PlayerConnection) {
    val likedIds by graph.library.likedIds.collectAsStateWithLifecycle()
    val downloads by graph.downloads.songs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val stack = remember { PageStack() }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<List<Track>?>(null) }
    val scope = rememberCoroutineScope()
    val search = remember { SearchState(graph.catalog, graph.library, scope) }
    val snackbar = remember { SnackbarHostState() }

    val actions = remember {
        SongActions(
            addToPlaylist = { addingToPlaylist = it },
            openArtist = { ref -> nowPlayingOpen = false; stack.open(Screen.Artist(ref)) },
            openPlaylist = { id -> stack.open(Screen.Playlist(id)) },
            openAlbum = { ref -> nowPlayingOpen = false; stack.open(Screen.Album(ref)) },
            openOnlinePlaylist = { ref -> stack.open(Screen.OnlinePlaylist(ref)) },
            openBrowse = { title, id, params -> stack.open(Screen.Browse(title, id, params)) },
            openReleases = { title, id, params -> stack.open(Screen.Releases(title, id, params)) },
            openMix = { mix -> stack.open(Screen.MixPage(mix)) },
            openPodcast = { ref -> nowPlayingOpen = false; stack.open(Screen.Podcast(ref)) },
            download = { tracks ->
                val problem = graph.downloads.download(tracks)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        problem ?: when {
                            tracks.size == 1 -> "Downloading ${tracks.first().title}"
                            else -> "Downloading ${tracks.size} songs"
                        } + if (graph.settings.downloadWifiOnly.value) " (on Wi-Fi)" else "",
                    )
                }
            },
            removeDownload = { id -> graph.downloads.remove(id) },
            say = { message ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(message)
                }
            },
        )
    }

    // Each tab and each open page keeps its own state (scroll position, chosen filter) while
    // another page is on top of it, so Back returns to where the listener was.
    val pages = rememberSaveableStateHolder()
    val closePage: () -> Unit = { stack.close()?.let { pages.removeState(it.key) } }
    val openTab: (Tab) -> Unit = { t ->
        stack.closeAll().forEach { pages.removeState(it.key) }
        tab = t
    }

    AuraTheme(player.current?.mediaMetadata?.artworkUri?.toString()) {
        CompositionLocalProvider(
            LocalLikedIds provides likedIds,
            LocalLibrary provides graph.library,
            LocalLyrics provides graph.lyrics,
            LocalSongActions provides actions,
            LocalDownloads provides downloads,
        ) {
            LaunchedEffect(player.error) {
                val message = player.error ?: return@LaunchedEffect
                player.clearError()
                snackbar.showSnackbar(message)
            }

            BackHandler(enabled = nowPlayingOpen || !stack.isEmpty || tab != Tab.Home) {
                when {
                    nowPlayingOpen -> nowPlayingOpen = false
                    !stack.isEmpty -> closePage()
                    else -> tab = Tab.Home
                }
            }

            // The mini player and the player share this layout, so the cover can travel between them.
            SharedTransitionLayout(Modifier.fillMaxSize().background(Palette.Ink)) {
                val sharedScope = this
                Scaffold(
                    containerColor = Palette.Ink,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column {
                            AnimatedVisibility(
                                visible = player.current != null && !nowPlayingOpen,
                                enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
                            ) {
                                CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalVisibilityScope provides this) {
                                    MiniPlayer(player, onOpen = { nowPlayingOpen = true })
                                }
                            }
                            NavigationBar(containerColor = Palette.Dusk) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t && stack.isEmpty,
                                        onClick = { openTab(t) },
                                        icon = { Icon(t.icon, contentDescription = null) },
                                        label = { Text(t.label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Palette.OnAura,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = Palette.Haze,
                                            unselectedTextColor = Palette.Haze,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        val top = stack.top
                        AnimatedContent(
                            targetState = Shown(top?.key ?: "tab:${tab.name}", top?.screen, tab, stack.pages.size),
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = { pageTransition(initialState.depth, targetState.depth) },
                            contentKey = { it.key },
                            label = "page",
                        ) { page ->
                            pages.SaveableStateProvider(page.key) {
                                when (val screen = page.screen) {
                                    is Screen.Artist -> ArtistScreen(graph, player, screen.ref, onBack = closePage)
                                    is Screen.Playlist -> PlaylistScreen(graph, player, screen.id, onBack = closePage)
                                    is Screen.Album -> AlbumScreen(graph, player, screen.ref, onBack = closePage)
                                    is Screen.OnlinePlaylist -> OnlinePlaylistScreen(graph, player, screen.ref, onBack = closePage)
                                    is Screen.Browse -> BrowseScreen(graph, player, screen.title, screen.browseId, screen.params, onBack = closePage)
                                    is Screen.Releases -> ReleasesScreen(graph, screen.title, screen.browseId, screen.params, onBack = closePage)
                                    is Screen.MixPage -> MixScreen(graph, player, screen.mix, onBack = closePage)
                                    is Screen.Podcast -> PodcastScreen(graph, player, screen.ref, onBack = closePage)
                                    Screen.Settings -> SettingsScreen(graph, onBack = closePage, onSignIn = { stack.open(Screen.Login) })
                                    Screen.Login -> LoginScreen(graph, onBack = closePage)
                                    Screen.Stats -> StatsScreen(graph, player, onBack = closePage)
                                    null -> when (page.tab) {
                                        Tab.Home -> HomeScreen(graph, player, onOpenSearch = { tab = Tab.Search })
                                        Tab.Search -> SearchScreen(search, player, onOpenArtist = actions.openArtist)
                                        Tab.Podcasts -> PodcastsScreen(graph, player)
                                        Tab.Library -> LibraryScreen(
                                            graph,
                                            player,
                                            onOpenPlaylist = actions.openPlaylist,
                                            onOpenSettings = { stack.open(Screen.Settings) },
                                            onOpenStats = { stack.open(Screen.Stats) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = nowPlayingOpen && player.current != null,
                    enter = slideInVertically(tween(320)) { it / 5 } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(260)) { it / 5 } + fadeOut(tween(200)),
                ) {
                    CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalVisibilityScope provides this) {
                        NowPlayingScreen(graph, player, onClose = { nowPlayingOpen = false })
                    }
                }
            }

            addingToPlaylist?.let { tracks ->
                AddToPlaylistSheet(graph.library, tracks, onDismiss = { addingToPlaylist = null }, say = actions.say)
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlayerConnection, onOpen: () -> Unit) {
    val item = player.current ?: return
    val meta = item.mediaMetadata
    val position = rememberPositionMs(player)
    // Swipe left for the next song, right for the previous one.
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (kotlin.math.abs(offset.value) / (threshold * 3)).coerceIn(0f, 0.6f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = offset.value
                        scope.launch {
                            when {
                                dx < -threshold -> player.next()
                                dx > threshold -> player.previous()
                            }
                            offset.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + amount) }
                    },
                )
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Raised)
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(meta.artworkUri?.toString(), MINI_COVER, Modifier.playerCover())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    meta.artist?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlay, modifier = Modifier.semantics { contentDescription = if (player.playing) "Pause" else "Play" }) {
                PlayPauseIcon(player.playing, Modifier.size(26.dp))
            }
            IconButton(onClick = player::next) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next song")
            }
        }
        val fraction = if (player.durationMs > 0) (position.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = LocalAura.current.accent,
            trackColor = Palette.Line,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
