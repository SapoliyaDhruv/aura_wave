package com.aurawave.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.ArtistRef
import com.aurawave.innertube.Match
import com.aurawave.player.AppGraph
import com.aurawave.player.data.db.SongPlays
import com.aurawave.player.data.db.toTrack
import com.aurawave.player.playback.PlayerConnection

private enum class Period(val label: String, val days: Int) {
    Week("This week", 7),
    Month("This month", 30),
    Year("This year", 365),
    All("All time", 0),
}

private data class ArtistPlays(val ref: ArtistRef, val plays: Int, val listenedMs: Long, val coverUrl: String)

@Composable
fun StatsScreen(graph: AppGraph, player: PlayerConnection, onBack: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(Period.Month) }
    val since = remember(period) { if (period.days == 0) 0L else System.currentTimeMillis() - period.days * 86_400_000L }
    val top by remember(since) { graph.library.topSongs(since, 50) }.collectAsStateWithLifecycle(initialValue = null)
    val listened by remember(since) { graph.library.listenedSince(since) }.collectAsStateWithLifecycle(initialValue = 0L)
    var artists by remember { mutableStateOf<List<ArtistPlays>>(emptyList()) }
    val actions = LocalSongActions.current

    LaunchedEffect(since, top) {
        artists = topArtists(graph.library.songPlaysSince(since))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Your listening", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Palette.OnAura,
                    ),
                )
            }
        }

        val songs = top ?: return@Column
        if (songs.isEmpty()) {
            MessageBox(
                title = "Nothing to count yet",
                body = "Songs you listen to for at least 30 seconds are counted here.",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatFigure(formatListened(listened), "listened")
                    StatFigure(songs.sumOf { it.plays }.toString(), if (songs.size == 50) "plays of your top 50" else "plays")
                    StatFigure(artists.size.toString(), "artists")
                }
            }
            if (artists.isNotEmpty()) {
                item { SectionTitle("Top artists") }
                itemsIndexed(artists.take(10), key = { _, a -> "artist:" + a.ref.name }) { i, a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.openArtist(a.ref) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Rank(i + 1)
                        Artwork(a.coverUrl, 48.dp, corner = 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.ref.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${a.plays} plays · ${formatListened(a.listenedMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { SectionTitle("Top songs") }
            val tracks = songs.map { it.song.toTrack() }
            itemsIndexed(songs, key = { _, s -> s.song.videoId }) { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(16.dp))
                    Rank(i + 1)
                    TrackRow(
                        track = tracks[i].copy(artist = "${tracks[i].artist} · ${s.plays} plays"),
                        isCurrent = s.song.videoId == player.current?.mediaId,
                        onClick = { player.play(tracks, i) },
                        onPlayNext = { player.playNext(tracks[i]) },
                        onAddToQueue = { player.addToQueue(tracks[i]) },
                        modifier = Modifier.padding(start = 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatFigure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Rank(n: Int) {
    Text(
        n.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 20.dp).padding(end = 10.dp),
    )
}

/** Plays per artist; a song with several artists counts for each of them. */
private fun topArtists(rows: List<SongPlays>): List<ArtistPlays> {
    class Acc(val ref: ArtistRef, var plays: Int = 0, var ms: Long = 0, var cover: String = "", var coverPlays: Int = 0)
    val byKey = LinkedHashMap<String, Acc>()
    for (r in rows) {
        val track = r.song.toTrack()
        val refs = track.artists.ifEmpty {
            track.artist.split(',', '&').map { it.trim() }.filter { it.isNotEmpty() && it != "Unknown artist" }.map { ArtistRef(it) }
        }
        for (ref in refs) {
            val key = Match.fold(ref.name)
            if (key.isEmpty()) continue
            val acc = byKey.getOrPut(key) { Acc(ref) }
            acc.plays += r.plays
            acc.ms += r.listenedMs
            if (r.plays > acc.coverPlays) {
                acc.cover = track.coverUrl
                acc.coverPlays = r.plays
            }
        }
    }
    return byKey.values.sortedWith(compareByDescending<Acc> { it.plays }.thenByDescending { it.ms })
        .map { ArtistPlays(it.ref, it.plays, it.ms, it.cover) }
}

/** "14 h 20 min", "35 min". */
private fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}
