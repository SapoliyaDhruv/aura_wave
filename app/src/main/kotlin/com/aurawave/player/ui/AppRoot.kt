package com.aurawave.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.PlaylistRef
import com.aurawave.innertube.PodcastRef
import com.aurawave.player.data.Mix
import com.aurawave.player.data.Release
import com.aurawave.innertube.Track
import kotlinx.coroutines.launch
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

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
    data object Search : Screen
    data object History : Screen
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

/** What fills the screen above the mini player: the tabs, or the newest page opened on top of them. */
private data class Shown(val key: String, val screen: Screen?, val depth: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(graph: AppGraph, player: PlayerConnection) {
    val likedIds by graph.library.likedIds.collectAsStateWithLifecycle()
    val downloads by graph.downloads.songs.collectAsStateWithLifecycle()
    val tabOrder by graph.settings.tabOrder.flow.collectAsStateWithLifecycle()
    val tabs = remember(tabOrder) { Tab.ordered(tabOrder) }
    val openTabName by graph.settings.openTab.flow.collectAsStateWithLifecycle()
    val homeIndex = tabs.indexOf(Tab.named(openTabName) ?: Tab.Home).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = homeIndex) { tabs.size }
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
    val openSearch: () -> Unit = {
        if (stack.top?.screen != Screen.Search) stack.open(Screen.Search)
        search.askForFocus()
    }
    val showTab: (Tab) -> Unit = { t ->
        stack.closeAll().forEach { pages.removeState(it.key) }
        tabs.indexOf(t).takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } }
    }
    // A new order in Settings: go back to the tab the app opens on.
    LaunchedEffect(tabs, homeIndex) { if (pager.currentPage != homeIndex) pager.scrollToPage(homeIndex) }

    // Remember the collection a queue was started from, so Home can offer to carry on with it.
    LaunchedEffect(player) {
        player.onCollectionQueued = { from, tracks ->
            graph.settings.lastPlayed.value = from.encode()
            graph.settings.lastPlayedIds.value = tracks.map { it.videoId }
        }
    }

    val accent by graph.settings.themeAccent.flow.collectAsStateWithLifecycle()
    val swipeSongs by graph.settings.swipeSongs.flow.collectAsStateWithLifecycle()
    AuraTheme(player.current?.mediaMetadata?.artworkUri?.toString(), fixed = Accent.named(accent).aura) {
        CompositionLocalProvider(
            LocalLikedIds provides likedIds,
            LocalLibrary provides graph.library,
            LocalLyrics provides graph.lyrics,
            LocalSongActions provides actions,
            LocalDownloads provides downloads,
            LocalIsPlaying provides (player.playing && !player.buffering),
            LocalSwipeSongs provides swipeSongs,
        ) {
            // Once a day, quietly: is there a newer version to offer?
            var updateOffer by remember { mutableStateOf<Release?>(null) }
            LaunchedEffect(Unit) {
                val release = graph.updates.checkIfDue() ?: return@LaunchedEffect
                val answer = snackbar.showSnackbar(
                    "AuraWave ${release.version} is available",
                    actionLabel = "See update",
                    duration = SnackbarDuration.Long,
                )
                if (answer == SnackbarResult.ActionPerformed) updateOffer = release
            }
            updateOffer?.let { UpdateDialog(graph, it, onDismiss = { updateOffer = null }) }

            LaunchedEffect(player.error) {
                val message = player.error ?: return@LaunchedEffect
                player.clearError()
                snackbar.showSnackbar(message)
            }

            // The player rises over the app as one seekable transition, so a swipe on the mini
            // player can carry it with the finger, and a tap or Back plays the same motion.
            val sheet = remember { SeekableTransitionState(nowPlayingOpen) }
            val sheetTransition = rememberTransition(sheet, label = "player")
            val pull = remember { PlayerPull(sheet, scope) }
            LaunchedEffect(nowPlayingOpen) {
                // A swipe's sink-back still running would otherwise take the transition back from this.
                pull.cancel()
                if (sheet.currentState != nowPlayingOpen || sheet.targetState != nowPlayingOpen) sheet.animateTo(nowPlayingOpen, PlayerSettle)
            }
            LaunchedEffect(player.current == null) {
                if (player.current == null) nowPlayingOpen = false
            }

            // Back closes the player, then the newest page, then returns to the tab the app opens on.
            BackHandler(enabled = nowPlayingOpen || !stack.isEmpty || pager.currentPage != homeIndex) {
                when {
                    nowPlayingOpen -> nowPlayingOpen = false
                    !stack.isEmpty -> closePage()
                    else -> scope.launch { pager.animateScrollToPage(homeIndex) }
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
                            // The slot appears and goes with the music. While the player is open it
                            // keeps its height under the player and only its contents fade, so the
                            // page behind is never re-laid out during the motion.
                            AnimatedVisibility(
                                visible = player.current != null,
                                enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
                            ) {
                                Box(Modifier.fillMaxWidth().height(MINI_SLOT + navigationBarHeight())) {
                                    sheetTransition.AnimatedVisibility(
                                        visible = { open -> !open },
                                        enter = fadeIn(tween(160, easing = LinearEasing)),
                                        exit = fadeOut(tween(160, easing = LinearEasing)),
                                    ) {
                                        CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalVisibilityScope provides this) {
                                            MiniPlayer(
                                                player,
                                                onOpen = { nowPlayingOpen = true },
                                                onStop = {
                                                    val stopped = player.stopAll()
                                                    scope.launch {
                                                        snackbar.currentSnackbarData?.dismiss()
                                                        val answer = snackbar.showSnackbar("Playback stopped", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                                        if (answer == SnackbarResult.ActionPerformed && stopped != null) player.restore(stopped)
                                                    }
                                                },
                                                onPull = pull::follow,
                                                onPullEnd = { open, from -> pull.release(open, from, onOpen = { nowPlayingOpen = true }) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    // The pages run down behind the mini player, which floats over them; each list
                    // ends with room for it (LocalListEnd), so its last row can be scrolled clear.
                    Box(Modifier.padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).fillMaxSize()) {
                        val top = stack.top
                        val listSpace = padding.calculateBottomPadding()
                        AnimatedContent(
                            targetState = Shown(top?.key ?: "tabs", top?.screen, stack.pages.size),
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = { pageTransition(initialState.depth, targetState.depth) },
                            contentKey = { it.key },
                            label = "page",
                        ) { page ->
                            pages.SaveableStateProvider(page.key) {
                                CompositionLocalProvider(LocalListEnd provides listSpace) {
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
                                    Screen.Search -> SearchScreen(search, player, onOpenArtist = actions.openArtist, onBack = closePage)
                                    Screen.History -> HistoryScreen(graph, player, onBack = closePage)
                                    null -> TabsHome(
                                        tabs = tabs,
                                        pager = pager,
                                        onSearch = openSearch,
                                        onHistory = { stack.open(Screen.History) },
                                        onStats = { stack.open(Screen.Stats) },
                                        onSettings = { stack.open(Screen.Settings) },
                                    ) { tab ->
                                        when (tab) {
                                            Tab.Home -> HomeScreen(
                                                graph,
                                                player,
                                                onOpenSearch = openSearch,
                                                onOpenLiked = { showTab(Tab.Liked) },
                                                onOpenHistory = { stack.open(Screen.History) },
                                            )
                                            Tab.Discover -> DiscoverScreen(graph, player, onOpenSearch = openSearch)
                                            Tab.Liked -> LikedTab(graph, player)
                                            Tab.Playlists -> PlaylistsTab(graph, player, onOpenPlaylist = actions.openPlaylist)
                                            Tab.Podcasts -> PodcastsScreen(graph, player)
                                            Tab.Albums -> AlbumsTab(graph)
                                            Tab.Artists -> ArtistsTab(graph)
                                            Tab.Downloads -> DownloadsTab(graph, player)
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }

                sheetTransition.AnimatedVisibility(
                    visible = { open -> open },
                    enter = PlayerEnter,
                    exit = PlayerExit,
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


/**
 * A swipe up on the mini player, carried through to the player: while the finger moves, the
 * player rises with it; when it lets go, the player either finishes opening or sinks back
 * from wherever the finger left it.
 */
private class PlayerPull(private val sheet: SeekableTransitionState<Boolean>, private val scope: CoroutineScope) {
    private var job: Job? = null

    /** [fraction] is how far the player has risen, 0 (closed) to 1 (open). */
    fun follow(fraction: Float) {
        job?.cancel()
        job = scope.launch { sheet.seekTo(fraction.coerceIn(0f, 1f), targetState = true) }
    }

    /** Stops a swipe's motion, so a tap or Back can take over the transition. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    fun release(open: Boolean, from: Float, onOpen: () -> Unit) {
        job?.cancel()
        if (open) {
            onOpen() // AppRoot finishes the opening from where the finger left it
            return
        }
        job = scope.launch {
            val back = Animatable(from.coerceIn(0f, 1f))
            val seeking = launch { snapshotFlow { back.value }.collect { sheet.seekTo(it, targetState = true) } }
            back.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            seeking.cancel()
            sheet.snapTo(false)
        }
    }
}

/** How the player settles once it is let go, or opened with a tap: quick at first, gentle at the end. */
private val PlayerSettle = tween<Float>(380, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))

/**
 * The player's own motion, in step with the finger: it travels the screen's full height at
 * an even pace, so a swipe of a given length lifts it by the same length. The ease comes
 * from [PlayerSettle], which drives this transition when nobody is touching it.
 */
private const val PLAYER_TRAVEL_MS = 360
private val PlayerEnter = slideInVertically(tween(PLAYER_TRAVEL_MS, easing = LinearEasing)) { it } +
    fadeIn(tween(140, easing = LinearEasing))
private val PlayerExit = slideOutVertically(tween(PLAYER_TRAVEL_MS, easing = LinearEasing)) { it } +
    fadeOut(tween(140, delayMillis = PLAYER_TRAVEL_MS - 140, easing = LinearEasing))

/** The mini player's slot above the tabs. Fixed, so the page underneath never shifts while the player opens or closes. */
private val MINI_HEIGHT = 60.dp
private val MINI_TOP_GAP = 6.dp
private val MINI_SLOT = MINI_HEIGHT + MINI_TOP_GAP
/** The mini player's corners; with its 60 dp height, nearly a pill, and close to the screen's own corners at the bottom. */
private val MINI_CORNER = 28.dp
/** The gap around the cover inside the mini player, so the cover's corners run parallel to the bar's. */
private val MINI_INSET = 8.dp

/**
 * The playing song, above the tabs. Tap it or swipe it up to open the player, which rises
 * with the finger; swipe it down to stop the music and put it away; swipe left for the next
 * song and right for the previous one. No progress here: the player shows it.
 */
@Composable
private fun MiniPlayer(
    player: PlayerConnection,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    /** How far a swipe up has lifted the player, 0 to 1. */
    onPull: (Float) -> Unit,
    /** The swipe up has ended: open the player, or let it sink back from [from]. */
    onPullEnd: (open: Boolean, from: Float) -> Unit,
) {
    val item = player.current ?: return
    val meta = item.mediaMetadata
    val aura = LocalAura.current
    val offset = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val stopAt = with(density) { 30.dp.toPx() }
    val maxDrop = with(density) { 56.dp.toPx() }
    val flingSpeed = with(density) { 700.dp.toPx() }
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // A soft pill, its ends following the same curve as the cover inside it, as One UI draws it.
    val shape = RoundedCornerShape(MINI_CORNER)
    // It runs down to the screen's edge. Where the phone shows a navigation bar, the pill
    // reaches under it and keeps its own buttons above it.
    val underBar = navigationBarHeight()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = MINI_TOP_GAP)
            .height(MINI_HEIGHT + underBar)
            .graphicsLayer {
                translationX = offset.value
                translationY = lift.value
                // Sideways it fades a little; on the way down it fades out, as it is on its way out.
                alpha = (1f - (kotlin.math.abs(offset.value) / (threshold * 3)).coerceIn(0f, 0.6f)) *
                    (1f - (lift.value / maxDrop).coerceIn(0f, 1f) * 0.85f)
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
            .pointerInput(screenHeight) {
                // Up, the player comes up under the finger. Down, the mini player sinks behind
                // the tabs and the music stops.
                val tracker = VelocityTracker()
                var travelled = 0f
                var pulled = false
                fun lifted() = (-travelled / screenHeight).coerceIn(0f, 1f)
                detectVerticalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        travelled = 0f
                        pulled = false
                    },
                    onDragEnd = {
                        val speed = tracker.calculateVelocity().y
                        if (pulled) {
                            val open = speed < -flingSpeed || (lifted() > 0.18f && speed < flingSpeed)
                            onPullEnd(open, lifted())
                        }
                        if (lift.value >= stopAt) onStop()
                        scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
                    },
                    onDragCancel = {
                        if (pulled) onPullEnd(false, lifted())
                        scope.launch { lift.animateTo(0f) }
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                        tracker.addPosition(change.uptimeMillis, Offset(0f, travelled))
                        if (travelled < 0f) {
                            pulled = true
                            onPull(lifted())
                            scope.launch { lift.snapTo(0f) }
                        } else {
                            if (pulled) onPull(0f)
                            scope.launch { lift.snapTo((travelled * 0.5f).coerceAtMost(maxDrop)) }
                        }
                    },
                )
            }
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            // One calm colour taken from the cover, with no outline.
            .background(lerp(Palette.Raised, aura.deep, 0.8f))
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = MINI_INSET, end = 4.dp, bottom = underBar), verticalAlignment = Alignment.CenterVertically) {
            Artwork(meta.artworkUri?.toString(), MINI_COVER, Modifier.playerCover(), corner = MINI_CORNER - MINI_INSET)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta.artist?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlay, modifier = Modifier.semantics { contentDescription = if (player.playing) "Pause" else "Play" }) {
                PlayPauseIcon(player.playing, Modifier.size(28.dp))
            }
            IconButton(onClick = player::next) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next song")
            }
        }
    }
}

@Composable
private fun navigationBarHeight() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
