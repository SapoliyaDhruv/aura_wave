package com.aurawave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.aurawave.innertube.InnerTube
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.aurawave.player.playback.PlayerConnection
import com.aurawave.player.AppGraph
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MediaItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateMapOf
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Track
import com.aurawave.player.playback.toTrack

@Composable
fun NowPlayingScreen(graph: AppGraph, player: PlayerConnection, onClose: () -> Unit) {
    val sleepTimer = graph.sleepTimer
    val item = player.current ?: return
    val meta = item.mediaMetadata
    var queueOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    val sleepLabel = rememberSleepLabel(sleepTimer)
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    val library = LocalLibrary.current
    val liked = item.mediaId in LocalLikedIds.current
    val actions = LocalSongActions.current
    val track = remember(item) { item.toTrack() }

    // Song/Video switch. A song from the audio catalogue is swapped for its official music
    // video (and back), remembering which song each video stands in for.
    var videoMode by rememberSaveable { mutableStateOf(false) }
    val swapped = remember { mutableStateMapOf<String, Track>() }
    var findingVideo by remember { mutableStateOf(false) }
    LaunchedEffect(item.mediaId, videoMode) {
        if (!videoMode || track.kind != Kind.SONG) return@LaunchedEffect
        findingVideo = true
        val video = graph.catalog.musicVideoFor(track)
        findingVideo = false
        if (player.current?.mediaId != track.videoId) return@LaunchedEffect
        if (video == null) {
            actions.say("No music video was found for this song.")
            videoMode = false
        } else {
            swapped[video.videoId] = track
            player.replaceCurrent(video, player.positionMs)
        }
    }
    val switchMode = { video: Boolean ->
        if (video != videoMode) {
            videoMode = video
            if (!video) swapped.remove(item.mediaId)?.let { song -> player.replaceCurrent(song, player.positionMs) }
        }
    }

    // Drag the top of the player (the bar and the cover) down to close it.
    val drag = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val dragToClose = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = {
                dragScope.launch { if (drag.value > closeAt) onClose() else drag.animateTo(0f) }
            },
            onDragCancel = { dragScope.launch { drag.animateTo(0f) } },
            onVerticalDrag = { change, amount ->
                change.consume()
                dragScope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
            },
        )
    }

    // This screen is drawn over the app, outside its Scaffold, so it sets its own text and icon colour.
    CompositionLocalProvider(LocalContentColor provides Palette.Moon) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = drag.value }) {
            AuraBackdrop(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    // Swallow taps so they never reach the screen underneath.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().then(dragToClose).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", modifier = Modifier.size(30.dp)) }
                    Text(
                        sleepLabel ?: "Now playing",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sleepLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { lyricsOpen = !lyricsOpen }) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            contentDescription = if (lyricsOpen) "Show cover" else "Show lyrics",
                            tint = if (lyricsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { queueOpen = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Up next") }
                    IconButton(onClick = { optionsOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Player options") }
                }

                SingleChoiceSegmentedButtonRow(Modifier.padding(bottom = 12.dp)) {
                    SegmentedButton(
                        selected = !videoMode,
                        onClick = { switchMode(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = Palette.OnAura),
                    ) { Text("Song") }
                    SegmentedButton(
                        selected = videoMode,
                        onClick = { switchMode(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = Palette.OnAura),
                    ) { Text("Video") }
                }

                // The cover (or the lyrics) takes whatever height is left, so the controls never fall off small screens.
                if (lyricsOpen) {
                    LyricsPanel(player, Modifier.weight(1f).fillMaxWidth())
                } else if (videoMode) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (findingVideo || track.kind == Kind.SONG) LoadingBox() else VideoPane(graph, player, item.mediaId)
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().then(dragToClose), contentAlignment = Alignment.Center) {
                        PlayerCover(meta.artworkUri?.toString(), playing = player.playing)
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            meta.title?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            meta.artist?.toString().orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LikeButton(liked, onToggle = { library.setLiked(track, !liked) })
                }

                Spacer(Modifier.height(12.dp))
                SeekBar(player)
                Spacer(Modifier.height(4.dp))
                Transport(player)
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (queueOpen) QueueSheet(player, onDismiss = { queueOpen = false })
    if (optionsOpen) {
        PlayerOptionsSheet(
            graph = graph,
            player = player,
            track = track,
            onDismiss = { optionsOpen = false },
            onLeavePlayer = onClose,
        )
    }
}

/** The player's backdrop: the song's colour fading into ink. On its own, so the colour's drift redraws only this. */
@Composable
private fun AuraBackdrop(modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    Box(modifier.background(Brush.verticalGradient(listOf(aura.deep, Palette.Ink))))
}

/**
 * The big cover. It arrives from the mini player, draws back a little while the music is
 * paused, and cross-fades to the next song's cover.
 */
@Composable
private fun PlayerCover(url: String?, playing: Boolean) {
    val scale = animateFloatAsState(
        if (playing) 1f else 0.9f,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "coverScale",
    )
    val smallPx = coverPx(MINI_COVER)
    Box(
        Modifier
            .playerCover()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Raised),
    ) {
        Crossfade(url, Modifier.fillMaxSize(), tween(450), label = "cover") { shown ->
            // The mini player's small copy is already in memory, so there is a picture from the
            // first frame of the way up. The sharp one is drawn over it once it has loaded.
            AsyncImage(
                model = InnerTube.bigCover(shown, smallPx),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AsyncImage(
                model = InnerTube.bigCover(shown, 720),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SeekBar(player: PlayerConnection) {
    val accent = LocalAura.current.accent
    val position = rememberPositionMs(player)
    val duration = player.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: position.toFloat()

    Slider(
        value = shown.coerceIn(0f, maxOf(duration, 1L).toFloat()),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seekTo(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..maxOf(duration, 1L).toFloat(),
        enabled = duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = Palette.Line,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Transport(player: PlayerConnection) {
    val accent = LocalAura.current.accent
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    // Episodes get jumps of 10 seconds back and 30 ahead instead of shuffle and repeat.
    val current = player.current
    val episode = remember(current) { current?.toTrack()?.kind == com.aurawave.innertube.Kind.EPISODE }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (episode) {
            IconButton(onClick = { player.seekBy(-10_000) }) { Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds") }
        } else IconButton(onClick = player::toggleShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = if (player.shuffle) "Turn shuffle off" else "Turn shuffle on", tint = if (player.shuffle) accent else quiet)
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous song", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = player::togglePlay,
            modifier = Modifier.size(72.dp).semantics { contentDescription = if (player.playing) "Pause" else "Play" },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Palette.OnAura),
        ) {
            if (player.buffering) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Palette.OnAura, strokeWidth = 3.dp)
            } else {
                PlayPauseIcon(player.playing, Modifier.size(38.dp))
            }
        }
        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next song", modifier = Modifier.size(36.dp))
        }
        if (episode) {
            IconButton(onClick = { player.seekBy(30_000) }) { Icon(Icons.Rounded.Forward30, contentDescription = "Ahead 30 seconds") }
        } else IconButton(onClick = player::cycleRepeat) {
            val label = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> "Repeat all"
                Player.REPEAT_MODE_ALL -> "Repeat this song"
                else -> "Turn repeat off"
            }
            Icon(
                if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = label,
                tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) quiet else accent,
            )
        }
    }
}

/** The queue: drag the handles to reorder, swipe a song away to remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: PlayerConnection, onDismiss: () -> Unit) {
    val actions = LocalSongActions.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = player.index.coerceAtLeast(0))

    // The sheet's own copy, so a drag is smooth; each entry gets a key that survives moves, even for a song queued twice.
    // Filled straight away, so the sheet opens at the playing song instead of at the top of an empty list.
    val entries = remember { mutableStateListOf<Pair<String, MediaItem>>().apply { addAll(keyed(player.queue)) } }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    val reorder = rememberReorderState(
        listState,
        firstIndex = 0,
        onMove = { from, to ->
            if (dragFrom < 0) dragFrom = from
            dragTo = to
            entries.add(to, entries.removeAt(from))
        },
        onDrop = {
            if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) player.move(dragFrom, dragTo)
            dragFrom = -1
            dragTo = -1
        },
    )
    LaunchedEffect(player.queue) {
        if (reorder.draggingKey != null) return@LaunchedEffect
        entries.clear()
        entries.addAll(keyed(player.queue))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Dusk) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(
                enabled = player.queue.isNotEmpty(),
                onClick = { actions.addToPlaylist(player.queue.map { it.toTrack() }) },
            ) { Text("Save as playlist") }
            TextButton(
                enabled = player.index < player.queue.lastIndex,
                onClick = { player.clearUpcoming(); actions.say("Cleared the songs after this one") },
            ) { Text("Clear") }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(entries, key = { _, e -> e.first }) { i, (key, item) ->
                val isCurrent = i == player.index
                val dismiss = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismiss.currentValue) {
                    if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) {
                        val at = entries.indexOfFirst { it.first == key }
                        if (at >= 0) player.remove(at)
                    }
                }
                SwipeToDismissBox(
                    state = dismiss,
                    enableDismissFromStartToEnd = !isCurrent,
                    enableDismissFromEndToStart = !isCurrent,
                    gesturesEnabled = !isCurrent && reorder.draggingKey == null,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(Palette.Raised).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                            Text("Remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    modifier = reorderableItem(reorder, key),
                ) {
                    QueueRow(item, isCurrent, onClick = { player.jumpTo(i) }) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = "Drag to move ${item.mediaMetadata.title}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.dragHandle(reorder, key).padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Each queue entry with a key that is unique even when a song is queued twice. */
private fun keyed(queue: List<MediaItem>): List<Pair<String, MediaItem>> {
    val seen = HashMap<String, Int>()
    return queue.map { item ->
        val n = seen.merge(item.mediaId, 1, Int::plus) ?: 1
        "${item.mediaId}#$n" to item
    }
}

@Composable
private fun QueueRow(item: MediaItem, isCurrent: Boolean, onClick: () -> Unit, handle: @Composable () -> Unit) {
    val meta = item.mediaMetadata
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Dusk)
            .clickable(onClickLabel = "Play ${meta.title}", onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(meta.artworkUri?.toString(), 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meta.title?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
        handle()
    }
}
