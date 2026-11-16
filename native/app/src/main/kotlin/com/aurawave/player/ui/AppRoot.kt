package com.aurawave.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.ArtistRef
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Search("Search", Icons.Rounded.Search),
    Library("Library", Icons.Rounded.LibraryMusic),
}

@Composable
fun AppRoot(graph: AppGraph, player: PlayerConnection) {
    val aura = rememberAura(player.current?.mediaMetadata?.artworkUri?.toString())
    val likedIds by graph.library.likedIds.collectAsStateWithLifecycle()
    AuraTheme(aura) {
        CompositionLocalProvider(
            LocalLikedIds provides likedIds,
            LocalLibrary provides graph.library,
            LocalLyrics provides graph.lyrics,
        ) {
            var tab by rememberSaveable { mutableStateOf(Tab.Home) }
            var artist by remember { mutableStateOf<ArtistRef?>(null) }
            var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val search = remember { SearchState(graph.catalog, scope) }
            val snackbar = remember { SnackbarHostState() }

            LaunchedEffect(player.error) {
                val message = player.error ?: return@LaunchedEffect
                player.clearError()
                snackbar.showSnackbar(message)
            }

            BackHandler(enabled = nowPlayingOpen || artist != null || tab != Tab.Home) {
                when {
                    nowPlayingOpen -> nowPlayingOpen = false
                    artist != null -> artist = null
                    else -> tab = Tab.Home
                }
            }

            Box(Modifier.fillMaxSize().background(Palette.Ink)) {
                Scaffold(
                    containerColor = Palette.Ink,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column {
                            if (player.current != null) MiniPlayer(player, onOpen = { nowPlayingOpen = true })
                            NavigationBar(containerColor = Palette.Dusk) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t && artist == null,
                                        onClick = { tab = t; artist = null },
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
                        val openArtist = { ref: ArtistRef -> artist = ref }
                        val shown = artist
                        when {
                            shown != null -> ArtistScreen(graph, player, shown, onBack = { artist = null })
                            tab == Tab.Home -> HomeScreen(graph, player, onOpenSearch = { tab = Tab.Search })
                            tab == Tab.Search -> SearchScreen(search, player, onOpenArtist = openArtist)
                            else -> LibraryScreen(graph, player)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = nowPlayingOpen && player.current != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    NowPlayingScreen(player, onClose = { nowPlayingOpen = false })
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlayerConnection, onOpen: () -> Unit) {
    val item = player.current ?: return
    val meta = item.mediaMetadata
    val position = rememberPositionMs(player)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Raised)
            .clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(meta.artworkUri?.toString(), 44.dp)
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
            IconButton(onClick = player::togglePlay) {
                Icon(
                    if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = player::next) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next song")
            }
        }
        val fraction = if (player.durationMs > 0) (position.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Palette.Line,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
