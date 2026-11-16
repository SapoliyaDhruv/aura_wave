package com.aurawave.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.Track
import com.aurawave.player.data.Library
import com.aurawave.player.data.Recommender
import com.aurawave.player.data.LyricsStore
import com.aurawave.player.playback.PlayerConnection
import kotlinx.coroutines.delay

/** Ids of the listener's liked songs, so any row can show whether it is liked. */
val LocalLikedIds = compositionLocalOf<Set<String>> { emptySet() }
val LocalLibrary = staticCompositionLocalOf<Library> { error("Library is provided by AppRoot") }
val LocalLyrics = staticCompositionLocalOf<LyricsStore> { error("LyricsStore is provided by AppRoot") }

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Removes "(From "Film")" style credits for compact display. */
fun shortTitle(title: String): String = Recommender.shortTitle(title)

/** The picture size, in pixels, to ask YouTube for when a cover is shown at [size]. */
@Composable
fun coverPx(size: Dp): Int = with(LocalDensity.current) { size.roundToPx() }.coerceIn(64, 720)

/** Cover art, requested at the size it is shown so lists stay light on data. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    AsyncImage(
        model = InnerTube.bigCover(url, coverPx(size)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Palette.Raised),
    )
}

@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    /** Menu entries that only make sense where the row is shown, like "Remove from this playlist". */
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
    /** Shown in place of the menu button (a drag handle while reordering). */
    trailing: (@Composable () -> Unit)? = null,
    /** Replaces the artist line, e.g. with the reason a song was recommended. */
    subtitle: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val library = LocalLibrary.current
    val actions = LocalSongActions.current
    val liked = track.videoId in LocalLikedIds.current
    val download = LocalDownloads.current[track.videoId]
    SwipeToAct(
        // Not while rows are being dragged to a new order: that drag and this swipe would fight.
        enabled = LocalSwipeSongs.current && trailing == null,
        liked = liked,
        onPlayNext = {
            onPlayNext()
            actions.say("${track.title} plays next")
        },
        onLike = { library.setLiked(track, !liked) },
        modifier = modifier,
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(track.coverUrl, 48.dp)
            if (isCurrent) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Ink.copy(alpha = 0.55f)))
                PlayingBars(moving = LocalIsPlaying.current, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val duration = if (track.durationSec > 0) " · ${formatTime(track.durationSec * 1000L)}" else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    download?.done == true -> Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    )
                    download?.active == true -> Icon(
                        Icons.Rounded.Downloading,
                        contentDescription = "Downloading",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    )
                }
            Text(
                text = subtitle ?: (track.artist + duration),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            }
        }
        if (trailing != null) {
            trailing()
            return@Row
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menuOpen = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuOpen = false; onAddToQueue() })
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menuOpen = false; actions.addToPlaylist(listOf(track)) })
                DropdownMenuItem(
                    text = { Text(if (liked) "Remove from Liked songs" else "Like") },
                    onClick = { menuOpen = false; library.setLiked(track, !liked) },
                )
                when {
                    download?.done == true || download?.active == true ->
                        DropdownMenuItem(text = { Text(if (download.done) "Remove download" else "Cancel download") }, onClick = { menuOpen = false; actions.removeDownload(track.videoId) })
                    else -> DropdownMenuItem(text = { Text("Download") }, onClick = { menuOpen = false; actions.download(listOf(track)) })
                }
                track.leadArtist()?.let { artist ->
                    DropdownMenuItem(text = { Text("Go to ${artist.name}") }, onClick = { menuOpen = false; actions.openArtist(artist) })
                }
                extraActions.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                }
            }
        }
    }
    }
}

/** Whether swiping a song in a list does anything (a setting). Provided by AppRoot. */
val LocalSwipeSongs = compositionLocalOf { true }

/**
 * Swipe a song right to play it next, left to like it. The row follows the finger and
 * uncovers what will happen; the icon lights up, with a tick of the phone, once letting go
 * will do it. The row always springs back: nothing is removed by a swipe.
 */
@Composable
private fun SwipeToAct(
    enabled: Boolean,
    liked: Boolean,
    onPlayNext: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    // +1 when letting go will queue the song, -1 when it will like it.
    var armed by remember { mutableIntStateOf(0) }
    val playNext by rememberUpdatedState(onPlayNext)
    val like by rememberUpdatedState(onLike)
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.matchParentSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Rounded.PlaylistPlay,
                contentDescription = null,
                tint = if (armed == 1) accent else Palette.Haze,
                modifier = Modifier.graphicsLayer { alpha = uncovered(offset.value, trigger) },
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (liked) Icons.Rounded.HeartBroken else Icons.Rounded.Favorite,
                contentDescription = null,
                tint = if (armed == -1) accent else Palette.Haze,
                modifier = Modifier.graphicsLayer { alpha = uncovered(-offset.value, trigger) },
            )
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when (armed) {
                                1 -> playNext()
                                -1 -> like()
                            }
                            armed = 0
                            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragCancel = {
                            armed = 0
                            scope.launch { offset.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            // The row lags the finger a little, so it feels held rather than loose.
                            val next = (offset.value + amount * 0.6f).coerceIn(-trigger * 1.3f, trigger * 1.3f)
                            scope.launch { offset.snapTo(next) }
                            val now = when {
                                next >= trigger -> 1
                                next <= -trigger -> -1
                                else -> 0
                            }
                            if (now != armed) {
                                armed = now
                                if (now != 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                    )
                },
        ) { content() }
    }
}

/** How visible an action's icon is: nothing until the row has moved off it, full when the action is armed. */
private fun uncovered(moved: Float, trigger: Float): Float = ((moved - trigger * 0.45f) / (trigger * 0.55f)).coerceIn(0f, 1f)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** A full-area message with one way forward: empty screens and failures. */
@Composable
fun MessageBox(title: String, body: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** The playback position, re-read every [intervalMs] while music plays and whenever it jumps. */
@Composable
fun rememberPositionMs(player: PlayerConnection, intervalMs: Long = 500): Long {
    var position by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(player.playing, player.buffering, player.positionTick, player.current?.mediaId) {
        while (true) {
            position = player.positionMs
            if (!player.playing) break
            delay(intervalMs)
        }
    }
    return position
}

fun friendlyError(e: Throwable): String =
    if (e is java.io.IOException) "Couldn't reach YouTube Music. Check your connection and try again."
    else "Something went wrong. Try again."
