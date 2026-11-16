package com.aurawave.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aurawave.innertube.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AuraWave's look: quiet ink surfaces, moonlight text, and one accent colour (the "aura")
 * taken from the playing song's cover. Carried over from the web app's design system.
 */
object Palette {
    val Ink = Color(0xFF14101B)
    val Dusk = Color(0xFF1D1826)
    val Raised = Color(0xFF282133)
    val Line = Color(0x17F3EFF8)
    val Moon = Color(0xFFF3EFF8)
    val Haze = Color(0xFFA69EB4)
    val Faint = Color(0xFF776F87)
    val OnAura = Color(0xFF17111F)
    val Danger = Color(0xFFFF9B9B)
}

data class Aura(val accent: Color, val deep: Color) {
    companion object {
        val Default = Aura(Color(0xFFD9A6FF), Color(0xFF3A2350))
        val Neutral = Aura(Color.hsl(270f, 0.30f, 0.80f), Color.hsl(270f, 0.18f, 0.22f))
    }
}

val LocalAura = compositionLocalOf { Aura.Default }

@Composable
fun AuraTheme(aura: Aura, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = aura.accent,
        onPrimary = Palette.OnAura,
        primaryContainer = aura.deep,
        onPrimaryContainer = Palette.Moon,
        secondary = aura.accent,
        onSecondary = Palette.OnAura,
        secondaryContainer = Palette.Raised,
        onSecondaryContainer = Palette.Moon,
        background = Palette.Ink,
        onBackground = Palette.Moon,
        surface = Palette.Ink,
        onSurface = Palette.Moon,
        surfaceVariant = Palette.Raised,
        onSurfaceVariant = Palette.Haze,
        surfaceContainerLowest = Palette.Ink,
        surfaceContainerLow = Palette.Dusk,
        surfaceContainer = Palette.Dusk,
        surfaceContainerHigh = Palette.Raised,
        surfaceContainerHighest = Palette.Raised,
        outline = Palette.Faint,
        outlineVariant = Palette.Line,
        error = Palette.Danger,
    )
    CompositionLocalProvider(LocalAura provides aura) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The accent for a cover image, eased in so the interface drifts from one song's colour to the next. */
@Composable
fun rememberAura(coverUrl: String?): Aura {
    val context = LocalContext.current
    var target by remember { mutableStateOf(Aura.Default) }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(InnerTube.bigCover(coverUrl, 96))
            .size(48)
            .allowHardware(false)
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return@LaunchedEffect
        target = withContext(Dispatchers.Default) { auraOf(image.toBitmap()) }
    }
    val accent by animateColorAsState(target.accent, tween(1200), label = "aura")
    val deep by animateColorAsState(target.deep, tween(1200), label = "auraDeep")
    return Aura(accent, deep)
}

/**
 * Picks the most characterful colour of a cover: a hue histogram weighted by
 * saturation, then fixed lightness so text on it always stays readable.
 */
private fun auraOf(bitmap: Bitmap): Aura {
    val size = 24
    val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)

    val weight = DoubleArray(24)
    val red = DoubleArray(24)
    val green = DoubleArray(24)
    val blue = DoubleArray(24)
    val hsl = FloatArray(3)
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        androidx.core.graphics.ColorUtils.RGBToHSL(r, g, b, hsl)
        val l = hsl[2]
        if (l < 0.08f || l > 0.94f) continue
        val w = hsl[1] * hsl[1] + 0.02
        val bucket = (hsl[0] / 15f).toInt() % 24
        weight[bucket] += w
        red[bucket] += r * w
        green[bucket] += g * w
        blue[bucket] += b * w
    }
    val top = weight.indices.maxBy { weight[it] }
    if (weight[top] < 0.5) return Aura.Neutral // a grey or near-black cover has no colour to borrow

    androidx.core.graphics.ColorUtils.RGBToHSL(
        (red[top] / weight[top]).toInt(), (green[top] / weight[top]).toInt(), (blue[top] / weight[top]).toInt(), hsl,
    )
    val sat = hsl[1].coerceIn(0.35f, 0.85f)
    return Aura(accent = Color.hsl(hsl[0], sat, 0.74f), deep = Color.hsl(hsl[0], minOf(0.6f, sat), 0.2f))
}
