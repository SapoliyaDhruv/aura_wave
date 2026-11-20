package com.aurawave.player.ui

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.aurawave.player.data.PlaySource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.data.LocalShelves
import com.aurawave.player.data.Mix
import com.aurawave.player.data.Pick
import com.aurawave.player.playback.PlayerConnection


/**
 * The tab the app opens on. First, where the listener left off: the playlist or album last
 * played from, a podcast episode part-way through, and the songs played lately. Below that,
 * what is new: the day's mix, the mixes made for the listener, and songs to try.
 */
@Composable
fun HomeScreen(graph: AppGraph, player: PlayerConnection, onOpenSearch: () -> Unit, onOpenLiked: () -> Unit, onOpenHistory: () -> Unit) {
    val lastPlayedText by graph.settings.lastPlayed.flow.collectAsStateWithLifecycle()
    val lastPlayed = remember(lastPlayedText) { PlaySource.decode(lastPlayedText) }
    val lastPlayedIds by graph.settings.lastPlayedIds.flow.collectAsStateWithLifecycle()
    val queueIds = remember(lastPlayedIds) { lastPlayedIds.toHashSet() }
    // A playlist played from and later deleted is not offered again.
    val playlistId = lastPlayed?.playlistId
    val playlistExists by remember(playlistId) {
        if (playlistId == null) flowOf(true) else graph.library.playlist(playlistId).map { it != null }
    }.collectAsStateWithLifecycle(initialValue = playlistId == null)
    val recent by graph.library.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val unfinished by graph.library.inProgress.collectAsStateWithLifecycle(initialValue = emptyList())
    val feed by graph.recommender.feed.collectAsStateWithLifecycle()
    val building by graph.recommender.building.collectAsStateWithLifecycle()
    val offline by graph.recommender.offline.collectAsStateWithLifecycle()
    var local by remember { mutableStateOf<LocalShelves?>(null) }
    val actions = LocalSongActions.current

    // The feed is kept on the phone and rebuilt only when it is old, so opening Home costs nothing.
    LaunchedEffect(Unit) { graph.recommender.refresh() }
    LaunchedEffect(recent?.firstOrNull()?.videoId) { local = graph.recommender.localShelves() }

    val recentList = recent ?: return // still reading the library: draw nothing rather than flash the empty state
    val f = feed
    if (recentList.isEmpty() && lastPlayed == null && unfinished.isEmpty() && f == null) {
        if (building) {
            LoadingBox()
        } else {
            MessageBox(
                title = "Start with a song you love",
                body = "Search for a song, an artist, or a line of the lyrics. What you play is kept here, so you can carry on later.",
                action = "Search music",
                onAction = onOpenSearch,
            )
        }
        return
    }
    val shown = recentList.take(RECENT_SHOWN)

    // Every mix in one row: the ones built from radio, and the ones from the listener's own history.
    val made = buildList {
        if (f != null) {
            addAll(f.mixes)
            if (f.becauseSeed != null && f.because.isNotEmpty()) {
                add(Mix("because", "Because you played ${shortTitle(f.becauseSeed.title)}", "Often played after it", f.because))
            }
        }
        local?.onRepeat?.takeIf { it.size >= 3 }?.let { add(Mix("on-repeat", "On repeat", "What you keep coming back to", it)) }
        local?.rediscover?.takeIf { it.size >= 3 }?.let { add(Mix("rediscover", "Rediscover", "Loved a while ago", it)) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = listEnd())) {
        lastPlayed?.takeIf { playlistExists }?.let { source ->
            item(key = "last-played") {
                ContinueCollection(source, loaded = player.current?.mediaId in queueIds, player = player) {
                    when {
                        source.playlistId != null -> actions.openPlaylist(source.playlistId)
                        source.album != null -> actions.openAlbum(source.album)
                        source.online != null -> actions.openOnlinePlaylist(source.online)
                        source.liked -> onOpenLiked()
                    }
                }
            }
        }
        unfinished.firstOrNull()?.let { (episode, fraction) ->
            item(key = "episode") { ContinueEpisode(episode, fraction, player) }
        }
        if (shown.isNotEmpty()) {
            item(key = "recent-header") { ShelfHeader("Recently played", onMore = if (recentList.size > RECENT_SHOWN) onOpenHistory else null) }
            itemsIndexed(shown, key = { _, t -> "recent:" + t.videoId }) { i, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.videoId == player.current?.mediaId,
                    onClick = { player.play(shown, i) },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }
        f?.hero?.let { hero -> item(key = "hero") { HeroCard(hero, player) } }
        if (made.isNotEmpty()) {
            item(key = "made-header") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Made for you", style = ShelfTitle, modifier = Modifier.weight(1f))
                    RefreshButton(building) { graph.recommender.refresh(force = true) }
                }
            }
            item(key = "made") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(made, key = { it.id }) { mix -> MixTile(mix) { actions.openMix(mix) } }
                }
            }
        }
        // A few as a taste; the whole list, with its reasons and "Not interested", is behind See all.
        val fresh = f?.freshFinds.orEmpty()
        if (fresh.isNotEmpty()) {
            item(key = "fresh-header") {
                ShelfHeader(
                    "Fresh finds",
                    onMore = if (fresh.size > FRESH_SHOWN) {
                        { actions.openMix(Mix("fresh", "Fresh finds", "Songs you have not played yet", fresh)) }
                    } else null,
                )
            }
            pickRows(fresh.take(FRESH_SHOWN), graph, player)
        }
        if (f == null && offline && !building) {
            item(key = "offline") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Suggestions need a connection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    RefreshButton(false) { graph.recommender.refresh(force = true) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pickRows(picks: List<Pick>, graph: AppGraph, player: PlayerConnection) {
    val tracks = picks.map { it.track }
    itemsIndexed(picks, key = { _, p -> "pick:" + p.track.videoId + p.reason.hashCode() }) { i, pick ->
        TrackRow(
            track = pick.track,
            isCurrent = pick.track.videoId == player.current?.mediaId,
            onClick = { player.play(tracks, i) },
            onPlayNext = { player.playNext(pick.track) },
            onAddToQueue = { player.addToQueue(pick.track) },
            subtitle = "${pick.track.artist} · ${pick.reason}",
            extraActions = notInterested(graph, pick.track),
        )
    }
}

/**
 * The playlist, album or liked songs the listener last played from. While that queue is still
 * loaded, the button plays and pauses it; otherwise the card opens the collection again.
 */
@Composable
private fun ContinueCollection(source: PlaySource, loaded: Boolean, player: PlayerConnection, onOpen: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .pressable(onClickLabel = "Open ${source.title}", onClick = onOpen)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(LocalAura.current.deep, Palette.Raised)))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(source.coverUrl.ifEmpty { null }, 64.dp, corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (loaded && player.playing) "Playing from" else "Continue where you left off",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(source.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (source.subtitle.isNotEmpty()) {
                Text(source.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loaded) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = player::togglePlay,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = if (player.playing) "Pause" else "Play" },
            ) {
                PlayPauseIcon(player.playing, Modifier.size(22.dp), color = Palette.OnAura)
            }
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The podcast episode the listener is part-way through, so picking it up again does not mean changing tabs. */
@Composable
private fun ContinueEpisode(episode: Track, fraction: Float, player: PlayerConnection) {
    val isCurrent = player.current?.mediaId == episode.videoId
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .pressable(onClickLabel = "Continue ${episode.title}") {
                if (isCurrent) player.togglePlay() else player.play(listOf(episode))
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Raised),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(episode.coverUrl, 52.dp, corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue listening", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(episode.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (episode.album.isNotEmpty()) {
                    Text(
                        episode.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                PlayPauseIcon(isCurrent && player.playing, Modifier.size(20.dp), color = Palette.OnAura)
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Palette.Line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

private const val RECENT_SHOWN = 5
private const val FRESH_SHOWN = 5

/** The day's main mix, large, with its covers and play buttons. */
@Composable
private fun HeroCard(mix: Mix, player: PlayerConnection) {
    val tracks = mix.picks.map { it.track }
    val actions = LocalSongActions.current
    val aura = LocalAura.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(aura.deep, Palette.Raised)))
            .clickable(onClickLabel = "Open ${mix.name}") { actions.openMix(mix) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverMosaic(tracks.map { it.coverUrl }, 104.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(mix.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                featuring(tracks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.play(tracks) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = { player.play(tracks.shuffled()) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle play", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun featuring(tracks: List<Track>): String =
    tracks.map { shortTitle(it.title) }.distinct().take(4).joinToString(", ") + " and more"

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val angle = if (spinning) {
        val t = rememberInfiniteTransition(label = "refresh")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin").value
    } else 0f
    IconButton(onClick = onClick, enabled = !spinning) {
        Icon(Icons.Rounded.Refresh, contentDescription = "Get new picks", modifier = Modifier.rotate(angle))
    }
}
