package com.aurawave.player.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlin.math.sin
import kotlinx.coroutines.launch

// The app's motion, in one place. Everything here is short, and animates a small part of
// the screen (or only how it is drawn), so none of it brings back whole-screen redraws.

// ---------- the cover travelling between the mini player and the player ----------

/** Set by AppRoot around the mini player and the player; null anywhere else. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The size the mini player shows the cover at. The player asks for the same picture first, since it is already in memory. */
val MINI_COVER = 44.dp

/**
 * Marks the playing song's cover. When the player opens, the cover grows out of the
 * mini player into its place, and shrinks back when the player closes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerCover(): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val visibility = LocalVisibilityScope.current ?: return this
    return with(shared) {
        this@playerCover.sharedElement(rememberSharedContentState(key = "player-cover"), visibility, CoverTravel)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverTravel = BoundsTransform { _, _ -> spring(dampingRatio = 0.86f, stiffness = 360f) }

// ---------- pages ----------

/** A page opening slides in a little from the right; going back slides it away; tabs just fade. */
fun pageTransition(fromDepth: Int, toDepth: Int): ContentTransform = when {
    toDepth > fromDepth ->
        (fadeIn(tween(220, delayMillis = 50)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith fadeOut(tween(110))
    toDepth < fromDepth ->
        (fadeIn(tween(200)) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 10 }))
            .apply { targetContentZIndex = -1f } // the page being closed leaves over the one it uncovers
    else -> fadeIn(tween(160)) togetherWith fadeOut(tween(110))
}

// ---------- buttons ----------

/**
 * Play and pause as one shape: the triangle splits down the middle and its halves
 * straighten into the two bars, and back.
 */
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val progress = animateFloatAsState(if (playing) 1f else 0f, tween(220), label = "playPause")
    val path = remember { Path() }
    Canvas(modifier) {
        val p = progress.value // read while drawing, so the morph redraws this icon and nothing else
        val side = size.minDimension
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        fun point(play: Offset, pause: Offset) =
            Offset(left + (play.x + (pause.x - play.x) * p) * side, top + (play.y + (pause.y - play.y) * p) * side)

        path.rewind()
        for (half in HALVES) {
            half.forEachIndexed { i, (play, pause) ->
                val at = point(play, pause)
                if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
            }
            path.close()
        }
        drawPath(path, color)
        // A thin outline in the same colour rounds the corners and hides the seam between the halves.
        drawPath(path, color, style = Stroke(width = side * 0.05f, join = StrokeJoin.Round))
    }
}

/** Each half as four corners, each given as (where it is for Play, where it is for Pause), in a 1×1 square. */
private val HALVES = listOf(
    listOf(
        Offset(0.34f, 0.22f) to Offset(0.27f, 0.23f),
        Offset(0.56f, 0.36f) to Offset(0.41f, 0.23f),
        Offset(0.56f, 0.64f) to Offset(0.41f, 0.77f),
        Offset(0.34f, 0.78f) to Offset(0.27f, 0.77f),
    ),
    listOf(
        Offset(0.56f, 0.36f) to Offset(0.59f, 0.23f),
        Offset(0.78f, 0.50f) to Offset(0.73f, 0.23f),
        Offset(0.78f, 0.50f) to Offset(0.73f, 0.77f),
        Offset(0.56f, 0.64f) to Offset(0.59f, 0.77f),
    ),
)

/** The heart. It pops when a song is liked: a quick squeeze, then a small overshoot. */
@Composable
fun LikeButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, iconSize: Dp = 28.dp) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            if (!liked) {
                scope.launch {
                    scale.snapTo(0.6f)
                    scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                }
            }
            onToggle()
        },
        modifier = modifier,
    ) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (liked) "Remove from Liked songs" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = Modifier.size(iconSize).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}

// ---------- the seek bar ----------

/** Whether music is playing right now; rows use it to animate the song that is on. Provided by AppRoot. */
val LocalIsPlaying = compositionLocalOf { false }

/**
 * The seek bar: a thick, rounded track. The part already played carries water on its top
 * edge, three soft layers of waves drifting at their own pace; the bottom edge stays
 * straight. Paused, the water settles lower and stills. A slim upright thumb sits between
 * the played part and the rest, with a small gap on each side. Drag or tap to seek.
 *
 * It redraws itself on every frame while playing, and nothing around it: the water's
 * movement and the position are read only while drawing.
 */
@Composable
fun WaveSeekBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    color: Color,
    /** The exact position right now, asked for on each frame so the played part's end glides rather than steps. */
    livePositionMs: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = rememberWaveFlow(playing, livePositionMs, positionMs)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val dragging = dragFraction != null
    val thumb = animateFloatAsState(if (dragging) 1.3f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "thumb")
    val enabled = durationMs > 0
    val quiet = Palette.Moon.copy(alpha = 0.16f)
    val path = remember { Path() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                contentDescription = "Position in the song"
                progressBarRangeInfo = ProgressBarRangeInfo(positionMs.toFloat(), 0f..maxOf(durationMs, 1L).toFloat())
                setProgress { target ->
                    if (enabled) onSeek(target.toLong().coerceIn(0, durationMs))
                    enabled
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                detectTapGestures { at -> onSeek(((at.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { at -> dragFraction = (at.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val track = TRACK.toPx()
        val thumbWidth = THUMB_WIDTH.toPx()
        val inset = track / 2 + thumbWidth
        val width = size.width - inset * 2
        // The track sits a little low, so the water has room above it.
        val mid = size.height / 2 + 4.dp.toPx()
        val position = if (playing) flow.live.longValue else positionMs
        val fraction = dragFraction ?: if (enabled) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val at = inset + width * fraction
        val clear = GAP.toPx() + thumbWidth / 2 + track / 2
        val playedEnd = at - clear
        val restStart = at + clear

        // What is left to play.
        if (restStart < inset + width) {
            drawLine(quiet, Offset(restStart, mid), Offset(inset + width, mid), strokeWidth = track, cap = StrokeCap.Round)
        }
        // What has been played: the water behind, then the bar, then the front ripple on top of it.
        if (playedEnd > inset) {
            val top = mid - track / 2
            val time = flow.time.floatValue
            val height = flow.height.value
            WATER.forEachIndexed { i, layer ->
                if (i == WATER.lastIndex) {
                    drawLine(color, Offset(inset, mid), Offset(playedEnd, mid), strokeWidth = track, cap = StrokeCap.Round)
                }
                drawWater(path, inset, playedEnd, top, mid, time, height, layer, color)
            }
        }
        val thumbHeight = THUMB_HEIGHT.toPx() * thumb.value
        drawRoundRect(
            color,
            topLeft = Offset(at - thumbWidth / 2, mid - thumbHeight / 2),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2),
        )
    }
}

/** What moves the water: how long it has flowed, how high it stands, and where the song is on this frame. */
private class WaveFlow(val time: MutableFloatState, val live: MutableLongState, val height: State<Float>)

@Composable
private fun rememberWaveFlow(playing: Boolean, livePositionMs: () -> Long, positionMs: Long): WaveFlow {
    val time = remember { mutableFloatStateOf(0f) }
    val live = remember { mutableLongStateOf(positionMs) }
    // Paused, the water stays, only lower and still: a flat line reads as nothing playing at all.
    val height = animateFloatAsState(if (playing) 1f else WAVE_AT_REST, spring(dampingRatio = 0.9f, stiffness = 60f), label = "waveHeight")
    val position = rememberUpdatedState(livePositionMs)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Every layer turns a whole number of times in WATER_CYCLE seconds, so wrapping the clock leaves no seam.
            time.floatValue = (time.floatValue + (now - last) / 1e9f) % WATER_CYCLE
            live.longValue = position.value()
            last = now
        }
    }
    return remember { WaveFlow(time, live, height) }
}

/** One layer of water: how high its crests rise, how far apart they are, how fast they drift, and how see-through it is. */
private class WaterLayer(val rise: Dp, val length: Dp, val turnsPerSecond: Float, val alpha: Float, val offset: Float)

// Back to front. The back layers are taller, slower and fainter; the front one is a low ripple in full colour.
private val WATER = listOf(
    WaterLayer(rise = 7.dp, length = 58.dp, turnsPerSecond = 0.25f, alpha = 0.16f, offset = 0f),
    WaterLayer(rise = 5.dp, length = 40.dp, turnsPerSecond = 0.40f, alpha = 0.32f, offset = 2.1f),
    WaterLayer(rise = 2.5.dp, length = 28.dp, turnsPerSecond = 0.55f, alpha = 1f, offset = 4.2f),
)
private const val WATER_CYCLE = 20f

/**
 * Fills one layer from the bar's centre line up to its wavy top, between [start] and
 * [end]. The waves only ever rise above [top], so the bar's lower edge stays straight,
 * and they calm to nothing at both ends so the water meets the bar's round caps.
 */
private fun DrawScope.drawWater(
    path: Path,
    start: Float,
    end: Float,
    top: Float,
    mid: Float,
    time: Float,
    height: Float,
    layer: WaterLayer,
    color: Color,
) {
    if (end - start < 1f) return
    val rise = layer.rise.toPx() * height
    val length = layer.length.toPx()
    val phase = time * layer.turnsPerSecond * TWO_PI + layer.offset
    val calmIn = 14.dp.toPx()
    val calmOut = 22.dp.toPx()
    path.rewind()
    path.moveTo(start, mid)
    var x = start
    while (true) {
        val ease = smooth((x - start) / calmIn) * smooth((end - x) / calmOut)
        val crest = (1f + sin((x - start) / length * TWO_PI - phase)) / 2f
        path.lineTo(x, top - rise * crest * ease)
        if (x >= end) break
        x = minOf(x + 2f, end)
    }
    path.lineTo(end, mid)
    path.close()
    drawPath(path, color.copy(alpha = color.alpha * layer.alpha))
}

private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private val TRACK = 6.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 22.dp
private val GAP = 3.dp

/** How high the water stands while paused, as a share of its playing height. */
private const val WAVE_AT_REST = 0.4f
private const val TWO_PI = (2 * Math.PI).toFloat()


/**
 * A tile or card that gives a little under a finger and springs back, so a press is felt
 * before the page changes.
 */
@Composable
fun Modifier.pressable(onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * Three bars that rise and fall, drawn over the cover of the song that is on. They move
 * while the music plays and rest when it is paused.
 */
@Composable
fun PlayingBars(moving: Boolean, modifier: Modifier = Modifier, color: Color = Palette.Moon) {
    val turn = remember { mutableFloatStateOf(0f) }
    val height = animateFloatAsState(if (moving) 1f else 0f, tween(300), label = "barsHeight")
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            turn.floatValue = (turn.floatValue + (now - last) / 1e9f * 1.4f * TWO_PI) % (TWO_PI * 6)
            last = now
        }
    }
    Canvas(modifier) {
        val bar = size.width / 5f
        for (i in 0 until 3) {
            // Each bar keeps its own pace, so they never move as one.
            val swing = (sin(turn.floatValue * BAR_PACE[i] + i * 1.7f) + 1f) / 2f
            val tall = size.height * (0.28f + 0.72f * swing * height.value)
            drawRoundRect(
                color,
                topLeft = Offset(i * bar * 2f, size.height - tall),
                size = androidx.compose.ui.geometry.Size(bar, tall),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2f),
            )
        }
    }
}

private val BAR_PACE = floatArrayOf(1f, 1.5f, 0.75f)
