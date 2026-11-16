package com.aurawave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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

@Composable
fun NowPlayingScreen(player: PlayerConnection, onClose: () -> Unit) {
    val item = player.current ?: return
    val meta = item.mediaMetadata
    val aura = LocalAura.current
    var queueOpen by remember { mutableStateOf(false) }

    // This screen is drawn over the app, outside its Scaffold, so it sets its own text and icon colour.
    CompositionLocalProvider(LocalContentColor provides Palette.Moon) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(aura.deep, Palette.Ink)))
            // Swallow taps so they never reach the screen underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", modifier = Modifier.size(30.dp)) }
            Text(
                "Now playing",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = { queueOpen = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Up next") }
        }

        // The cover takes whatever height is left, so the controls never fall off small screens.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Artwork(meta.artworkUri?.toString(), minOf(maxWidth, maxHeight), corner = 16.dp)
        }

        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth()) {
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

        Spacer(Modifier.height(12.dp))
        SeekBar(player)
        Spacer(Modifier.height(4.dp))
        Transport(player)
        Spacer(Modifier.height(28.dp))
    }
    }

    if (queueOpen) QueueSheet(player, onDismiss = { queueOpen = false })
}

@Composable
private fun SeekBar(player: PlayerConnection) {
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
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
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
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = player::toggleShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = if (player.shuffle) "Turn shuffle off" else "Turn shuffle on", tint = if (player.shuffle) accent else quiet)
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous song", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = player::togglePlay,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Palette.OnAura),
        ) {
            if (player.buffering) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Palette.OnAura, strokeWidth = 3.dp)
            } else {
                Icon(
                    if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.playing) "Pause" else "Play",
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next song", modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = player::cycleRepeat) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: PlayerConnection, onDismiss: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = player.index.coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Dusk) {
        Text(
            "Up next",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(player.queue) { i, item ->
                val meta = item.mediaMetadata
                val isCurrent = i == player.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Play ${meta.title}") { player.jumpTo(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                }
            }
        }
    }
}
