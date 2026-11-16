package com.aurawave.player.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.Episode
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.PodcastRef
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.Locale

/** The Podcasts tab: what the listener is in the middle of, what is new from their shows, then YouTube Music's podcasts. */
@Composable
fun PodcastsScreen(graph: AppGraph, player: PlayerConnection) {
    val following by graph.library.followedPodcasts.collectAsStateWithLifecycle(initialValue = emptyList())
    val inProgress by graph.library.inProgress.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by graph.library.episodeProgress.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    var fresh by remember { mutableStateOf<List<Episode>?>(null) }
    var allUnfinished by rememberSaveable { mutableStateOf(false) }
    var allNew by rememberSaveable { mutableStateOf(false) }
    val home = rememberLoader("podcasts", "Podcasts could not be loaded.") { graph.catalog.shelves(InnerTube.PODCASTS).ifEmpty { null } }

    LaunchedEffect(following.map { it.id }) {
        if (following.isEmpty()) {
            fresh = emptyList()
            return@LaunchedEffect
        }
        fresh = newEpisodes(graph, following)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = listEnd())) {
        // The listener's own shows first, then where they left off and what is new: a few of
        // each, with the rest one tap away, so the page does not turn into a long list.
        if (following.isNotEmpty()) {
            item { ShelfHeader("Your shows") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(following, key = { it.id }) { show -> Tile(show.coverUrl, show.title, show.author, size = 112.dp) { actions.openPodcast(show) } }
                }
            }
        }
        if (inProgress.isNotEmpty()) {
            item { ShelfHeader("Continue listening") }
            items(if (allUnfinished) inProgress else inProgress.take(FEW_UNFINISHED), key = { "c:" + it.first.videoId }) { (track, fraction) ->
                EpisodeRow(graph, player, track, date = "", fraction = fraction, played = false)
            }
            if (!allUnfinished && inProgress.size > FEW_UNFINISHED) {
                item { ShowMore("Show ${inProgress.size - FEW_UNFINISHED} more") { allUnfinished = true } }
            }
        }
        val newOnes = fresh.orEmpty().filter { progress[it.track.videoId]?.played != true }.take(15)
        if (newOnes.isNotEmpty()) {
            item { ShelfHeader("New episodes") }
            items(if (allNew) newOnes else newOnes.take(FEW_NEW), key = { "n:" + it.track.videoId }) { e ->
                EpisodeRow(graph, player, e.track, e.date, fraction = progressFraction(graph, e.track), played = false)
            }
            if (!allNew && newOnes.size > FEW_NEW) {
                item { ShowMore("Show ${newOnes.size - FEW_NEW} more") { allNew = true } }
            }
        }
        if (following.isEmpty()) {
            item {
                Text(
                    "Follow a show to see its new episodes here. Search for podcasts with the Podcasts filter in Search, or start below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        when (val s = home.state) {
            Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = home.retry) { Text("Try again") }
                }
            }
            is Load.Ready -> {
                // A taste of YouTube Music's podcasts; the whole catalogue is behind one button.
                s.value.take(FEW_SHELVES).forEach { shelf -> item { ShelfRow(shelf, player) } }
                if (s.value.size > FEW_SHELVES) {
                    item { ShowMore("Explore more podcasts") { actions.openBrowse("Podcasts", InnerTube.PODCASTS, "") } }
                }
            }
        }
    }
}

@Composable
private fun ShowMore(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp)) { Text(label) }
}

private const val FEW_UNFINISHED = 3
private const val FEW_NEW = 5
private const val FEW_SHELVES = 3

/** The latest episodes of the followed shows, newest first. */
private suspend fun newEpisodes(graph: AppGraph, shows: List<PodcastRef>): List<Episode> = coroutineScope {
    val now = System.currentTimeMillis()
    shows.take(MAX_SHOWS).map { show ->
        async { runCatching { graph.catalog.latestEpisodes(show.id) }.getOrDefault(emptyList()).take(3) }
    }.awaitAll().flatten()
        .distinctBy { it.track.videoId }
        .map { it to episodeTime(it.date, now) }
        .filter { (_, at) -> at == 0L || now - at < 60L * DAY }
        .sortedByDescending { it.second }
        .map { it.first }
}

private fun progressFraction(graph: AppGraph, track: Track): Float {
    val p = graph.library.episodeProgress.value[track.videoId] ?: return 0f
    return if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
}

@Composable
fun PodcastScreen(graph: AppGraph, player: PlayerConnection, ref: PodcastRef, onBack: () -> Unit) {
    val loader = rememberLoader(ref.id, "This show could not be found.") { graph.catalog.podcast(ref.id) }
    val following by graph.library.followedPodcastIds.collectAsStateWithLifecycle()
    val progress by graph.library.episodeProgress.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(onBack)
        LoadContent(loader, "Couldn't open ${ref.title}") { page ->
            val show = page.podcast.copy(coverUrl = page.podcast.coverUrl.ifEmpty { ref.coverUrl })
            val isFollowing = show.id in following
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = listEnd())) {
                item {
                    CollectionHeader(
                        title = show.title,
                        subtitle = listOf(show.author, "${page.episodes.size} episodes").filter { it.isNotEmpty() }.joinToString(" · "),
                        coverUrl = show.coverUrl,
                        onPlay = null,
                        onShuffle = null,
                        extra = {
                            OutlinedButton(onClick = {
                                graph.library.setFollowingPodcast(show, !isFollowing)
                                actions.say(if (isFollowing) "Unfollowed ${show.title}" else "Following ${show.title}")
                            }) { Text(if (isFollowing) "Following" else "Follow") }
                            val latest = page.episodes.firstOrNull { progress[it.track.videoId]?.played != true }
                            if (latest != null) {
                                TextButton(onClick = { player.play(listOf(latest.track)) }) { Text("Play latest") }
                            }
                        },
                    )
                    if (page.description.isNotBlank()) {
                        Text(
                            page.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable(onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(page.episodes, key = { it.track.videoId }) { e ->
                    val p = progress[e.track.videoId]
                    EpisodeRow(
                        graph, player, e.track, e.date,
                        fraction = if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f,
                        played = p?.played == true,
                        showName = false,
                    )
                }
            }
        }
    }
}

/** One episode: title, show, date and length, how far the listener got, and its menu. */
@Composable
fun EpisodeRow(
    graph: AppGraph,
    player: PlayerConnection,
    track: Track,
    date: String,
    fraction: Float,
    played: Boolean,
    showName: Boolean = true,
) {
    val actions = LocalSongActions.current
    val download = LocalDownloads.current[track.videoId]
    var menuOpen by remember { mutableStateOf(false) }
    val isCurrent = player.current?.mediaId == track.videoId
    Row(
        Modifier.fillMaxWidth().clickable { player.play(listOf(track)) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.coverUrl, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    played -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (played) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Played", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp).height(14.dp))
                }
                Text(
                    listOfNotNull(
                        track.album.takeIf { showName && it.isNotEmpty() },
                        date.ifEmpty { null },
                        track.durationSec.takeIf { it > 0 }?.let { formatDuration(it) },
                        "Downloaded".takeIf { download?.done == true },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (fraction > 0f && !played) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menuOpen = false; player.playNext(track) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuOpen = false; player.addToQueue(track) })
                DropdownMenuItem(
                    text = { Text(if (played) "Mark as not played" else "Mark as played") },
                    onClick = { menuOpen = false; graph.library.setPlayed(track, !played) },
                )
                DropdownMenuItem(
                    text = { Text(if (download?.done == true) "Remove download" else "Download") },
                    onClick = {
                        menuOpen = false
                        if (download?.done == true || download?.active == true) actions.removeDownload(track.videoId) else actions.download(listOf(track))
                    },
                )
                if (showName && track.albumId.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Go to show") },
                        onClick = { menuOpen = false; actions.openPodcast(PodcastRef(track.albumId, track.album, track.artist)) },
                    )
                }
            }
        }
    }
}

/** An episode in a row of tiles. */
@Composable
fun EpisodeTile(episode: Episode, player: PlayerConnection) {
    Tile(episode.track.coverUrl, episode.track.title, listOf(episode.track.album, episode.date).filter { it.isNotEmpty() }.joinToString(" · "), size = 160.dp) {
        player.play(listOf(episode.track))
    }
}

private const val MAX_SHOWS = 12
private const val DAY = 86_400_000L

private val AGO = Regex("""(\d+)\s*(min|m|h|hr|hour|d|day|w|wk|week|mo|month|y|yr|year)s?\s+ago""", RegexOption.IGNORE_CASE)
private val MONTH_DAY = Regex("""^([A-Za-z]{3})[a-z]*\s+(\d{1,2})(?:,\s*(\d{4}))?$""")
private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** "14h ago", "3d ago", "Apr 13", "Aug 25, 2023" to a time, or 0 when it cannot be read. */
fun episodeTime(text: String, now: Long = System.currentTimeMillis()): Long {
    AGO.find(text.lowercase(Locale.ROOT))?.let { m ->
        val n = m.groupValues[1].toLong()
        val unit = m.groupValues[2]
        val ms = when {
            unit.startsWith("mo") -> 30 * DAY
            unit.startsWith("mi") || unit == "m" -> 60_000L
            unit.startsWith("h") -> 3_600_000L
            unit.startsWith("d") -> DAY
            unit.startsWith("w") -> 7 * DAY
            else -> 365 * DAY
        }
        return now - n * ms
    }
    val m = MONTH_DAY.find(text.trim()) ?: return 0L
    val month = MONTHS.indexOf(m.groupValues[1].lowercase(Locale.ROOT)).takeIf { it >= 0 } ?: return 0L
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val year = m.groupValues[3].toIntOrNull() ?: cal.get(Calendar.YEAR)
    cal.set(year, month, m.groupValues[2].toInt(), 12, 0, 0)
    // "Dec 30" seen in January is last year's.
    if (m.groupValues[3].isEmpty() && cal.timeInMillis > now + DAY) cal.add(Calendar.YEAR, -1)
    return cal.timeInMillis
}
