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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
