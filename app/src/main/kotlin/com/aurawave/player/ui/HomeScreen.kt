package com.aurawave.player.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}

@Composable
fun HomeScreen(graph: AppGraph, player: PlayerConnection, onOpenSearch: () -> Unit) {
    val recent by graph.library.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val feed by graph.recommender.feed.collectAsStateWithLifecycle()
    val building by graph.recommender.building.collectAsStateWithLifecycle()
    val offline by graph.recommender.offline.collectAsStateWithLifecycle()
    var local by remember { mutableStateOf<LocalShelves?>(null) }
    val unfinished by graph.library.inProgress.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = LocalSongActions.current

    LaunchedEffect(Unit) { graph.recommender.refresh() }
    LaunchedEffect(recent?.firstOrNull()?.videoId) { local = graph.recommender.localShelves() }

    val recentList = recent ?: return // still reading the library: draw nothing rather than flash the empty state
    val f = feed
    if (recentList.isEmpty() && f == null) {
        when {
            building -> LoadingBox()
            else -> MessageBox(
                title = "Start with a song you love",
                body = if (offline) "Recommendations need a connection. Search for a song, or try again when you're online."
                else "Search for a song, an artist, or a line of the lyrics. This page fills in as you listen.",
                action = "Search music",
                onAction = onOpenSearch,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RefreshButton(building) { graph.recommender.refresh(force = true) }
            }
        }
        // The page is kept short on purpose: where the listener left off, one big mix, one row
        // of everything made for them, and a handful of new songs. The full history is in Library.
        if (recentList.isNotEmpty()) {
            item {
                // Taken once per visit, so the cards do not reshuffle each time a song changes.
                val quick = remember(recentList.size.coerceAtMost(QUICK_SHOWN)) { recentList.take(QUICK_SHOWN) }
                JumpBackIn(quick, player)
            }
        }

        unfinished.firstOrNull()?.let { (episode, fraction) ->
            item(key = "continue") { ContinueEpisode(episode, fraction, player) }
        }

        f?.hero?.let { hero -> item { HeroCard(hero, player) } }

        // Every mix in one row: the ones built from radio, and the ones from the listener's own history.
        val made = buildList {
            f?.mixes?.let(::addAll)
            if (f?.becauseSeed != null && f.because.isNotEmpty()) {
                add(Mix("because", "Because you played ${shortTitle(f.becauseSeed.title)}", "Often played after it", f.because))
            }
            local?.onRepeat?.takeIf { it.size >= 3 }?.let { add(Mix("on-repeat", "On repeat", "What you keep coming back to", it)) }
            local?.rediscover?.takeIf { it.size >= 3 }?.let { add(Mix("rediscover", "Rediscover", "Loved a while ago", it)) }
        }
        if (made.isNotEmpty()) {
            item { ShelfHeader("Made for you") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(made, key = { it.id }) { mix -> MixTile(mix) { actions.openMix(mix) } }
                }
            }
        }

        // A few as a taste; the whole list, with its reasons and "Not interested", is behind See all.
        if (!f?.freshFinds.isNullOrEmpty()) {
            val fresh = f!!.freshFinds
            item {
                ShelfHeader(
                    "Fresh finds",
                    onMore = if (fresh.size > FRESH_SHOWN) {
                        { actions.openMix(Mix("fresh", "Fresh finds", "Songs you have not played yet", fresh)) }
                    } else null,
                )
            }
            pickRows(fresh.take(FRESH_SHOWN), graph, player)
        }

        if (f == null && building) item { LoadingBox() }
        if (f == null && offline && !building) {
            item {
                Text(
                    "Recommendations will appear when you're online.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
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

/** The last few songs, two to a row and small: seen at a glance, played with one tap. */
@Composable
private fun JumpBackIn(tracks: List<Track>, player: PlayerConnection) {
    val currentId = player.current?.mediaId
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tracks.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { track ->
                    QuickCard(track, isCurrent = track.videoId == currentId, modifier = Modifier.weight(1f)) {
                        player.play(tracks, tracks.indexOf(track))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickCard(track: Track, isCurrent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(onClickLabel = "Play ${track.title}", onClick = onClick)
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Raised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(track.coverUrl, 56.dp, corner = 0.dp)
            if (isCurrent) {
                Box(Modifier.size(56.dp).background(Palette.Ink.copy(alpha = 0.55f)))
                PlayingBars(moving = LocalIsPlaying.current, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            shortTitle(track.title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
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

private const val QUICK_SHOWN = 6
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
        CoverMosaic(tracks.map { it.coverUrl }, 112.dp)
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
