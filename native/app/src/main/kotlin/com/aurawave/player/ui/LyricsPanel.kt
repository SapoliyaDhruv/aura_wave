package com.aurawave.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurawave.innertube.SongLyrics
import com.aurawave.player.playback.PlayerConnection
import com.aurawave.player.playback.toTrack
import kotlinx.coroutines.CancellationException

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object None : LyricsLoad
    data class Ready(val lyrics: SongLyrics) : LyricsLoad
}

/** Lyrics for the playing song. Synced lyrics follow the music; tapping a line jumps to it. */
@Composable
fun LyricsPanel(player: PlayerConnection, modifier: Modifier = Modifier) {
    val store = LocalLyrics.current
    val item = player.current ?: return
    var load by remember(item.mediaId) { mutableStateOf<LyricsLoad>(LyricsLoad.Loading) }
    LaunchedEffect(item.mediaId) {
        load = try {
            store.get(item.toTrack())?.let { LyricsLoad.Ready(it) } ?: LyricsLoad.None
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LyricsLoad.None
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val state = load) {
            LyricsLoad.Loading -> LoadingBox()
            LyricsLoad.None -> Text(
                "No lyrics found for this song.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            is LyricsLoad.Ready ->
                if (state.lyrics.isSynced) SyncedLyrics(state.lyrics, player) else PlainLyrics(state.lyrics)
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: SongLyrics, player: PlayerConnection) {
    val library = LocalLibrary.current
    val videoId = player.current?.mediaId.orEmpty()
    // The listener's correction for lyrics that run early or late on this song.
    val offset by remember(videoId) { library.lyricsOffset(videoId) }.collectAsStateWithLifecycle(initialValue = 0L)
    val position = rememberPositionMs(player, intervalMs = 200)
    // Lines light up a touch early, the way a singer breathes in before the word.
    val active = lyrics.lines.indexOfLast { it.timeMs + offset <= position + 250 }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val restingOffset = with(LocalDensity.current) { (maxHeight / 3).roundToPx() }
        LaunchedEffect(active) {
            // Keep the current line a third of the way down, unless the listener is browsing the lyrics.
            if (active >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem(active, -restingOffset)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
            itemsIndexed(lyrics.lines) { i, line ->
                // The line being sung eases up to full brightness and size; the rest sit dimmer and a touch smaller.
                val lit = animateFloatAsState(if (i == active) 1f else 0f, tween(280), label = "lyricLine")
                Text(
                    text = line.text.ifEmpty { "♪" },
                    fontSize = 22.sp,
                    fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Moon,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Jump to this line") { player.seekTo((line.timeMs + offset).coerceAtLeast(0)) }
                        .padding(vertical = 8.dp)
                        .graphicsLayer {
                            val t = lit.value
                            alpha = 0.38f + 0.62f * t
                            scaleX = 0.94f + 0.06f * t
                            scaleY = scaleX
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
            }
            item { LyricsSource(lyrics.source) }
        }
    }
    LyricsTiming(offset, onChange = { library.setLyricsOffset(videoId, it) })
    }
}

/** Shifts synced lyrics for this song in half-second steps. */
@Composable
private fun LyricsTiming(offset: Long, onChange: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { onChange(offset - STEP_MS) }) { Text("Show earlier") }
        Text(
            when {
                offset == 0L -> "Timing"
                offset > 0 -> "+%.1f s".format(offset / 1000f)
                else -> "%.1f s".format(offset / 1000f)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(enabled = offset != 0L, onClickLabel = "Reset lyrics timing") { onChange(0) }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
        TextButton(onClick = { onChange(offset + STEP_MS) }) { Text("Show later") }
    }
}

private const val STEP_MS = 500L

@Composable
private fun PlainLyrics(lyrics: SongLyrics) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        Text(lyrics.plain, fontSize = 18.sp, lineHeight = 28.sp, color = Palette.Moon)
        LyricsSource(lyrics.source)
    }
}

@Composable
private fun LyricsSource(source: String) {
    if (source.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text("Lyrics from $source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
