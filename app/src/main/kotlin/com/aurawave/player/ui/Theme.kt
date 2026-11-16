package com.aurawave.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.aurawave.player.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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

/** The old app's typefaces: Bricolage Grotesque for headings, Figtree for everything else. Both are variable fonts. */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weights: List<FontWeight>, extra: (FontWeight) -> Array<FontVariation.Setting> = { emptyArray() }) =
    FontFamily(weights.map { w -> Font(res, weight = w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight), *extra(w))) })

private val Figtree = variable(R.font.figtree, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold))

private val Bricolage = variable(R.font.bricolage_grotesque, listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)) {
    arrayOf(FontVariation.Setting("opsz", 36f))
}

private val AuraType: Typography = Typography().let { t ->
    fun TextStyle.ui() = copy(fontFamily = Figtree)
    fun TextStyle.display() = copy(fontFamily = Bricolage)
    t.copy(
        displayLarge = t.displayLarge.display(),
        displayMedium = t.displayMedium.display(),
        displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(),
        headlineMedium = t.headlineMedium.display(),
        headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(),
        titleMedium = t.titleMedium.ui(),
        titleSmall = t.titleSmall.ui(),
        bodyLarge = t.bodyLarge.ui(),
        bodyMedium = t.bodyMedium.ui(),
        bodySmall = t.bodySmall.ui(),
        labelLarge = t.labelLarge.ui(),
        labelMedium = t.labelMedium.ui(),
        labelSmall = t.labelSmall.ui(),
    )
}

/**
 * The app's colours for the playing song's cover. The colour scheme changes once per song.
 * The slow drift from one song's colour to the next is carried by [LocalAura] alone, so
 * only the few places that draw with it are redrawn while it eases, not every screen.
 */
@Composable
fun AuraTheme(coverUrl: String?, content: @Composable () -> Unit) {
    val target = rememberAuraOf(coverUrl)
    val scheme = remember(target) {
        darkColorScheme(
            primary = target.accent,
            onPrimary = Palette.OnAura,
            primaryContainer = target.deep,
            onPrimaryContainer = Palette.Moon,
            secondary = target.accent,
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
    }
    MaterialTheme(colorScheme = scheme, typography = AuraType) {
        DriftingAura(target, content)
    }
}

/** Eases [LocalAura] towards [target]. Only what reads [LocalAura] is redrawn on each step. */
@Composable
private fun DriftingAura(target: Aura, content: @Composable () -> Unit) {
    val accent by animateColorAsState(target.accent, tween(AURA_DRIFT_MS), label = "aura")
    val deep by animateColorAsState(target.deep, tween(AURA_DRIFT_MS), label = "auraDeep")
    CompositionLocalProvider(LocalAura provides Aura(accent, deep), content = content)
}

private const val AURA_DRIFT_MS = 1200

/** The accent for the playing song's cover; the default until the cover has been read. */
@Composable
private fun rememberAuraOf(coverUrl: String?): Aura {
    val last = remember { arrayOf(Aura.Default) }
    val picture = if (coverUrl.isNullOrEmpty()) "" else InnerTube.bigCover(coverUrl, 96)
    rememberCoverAura(picture)?.let { last[0] = it } // a song without a readable cover keeps the last song's colour
    return last[0]
}

/** Colours already worked out, by picture address, so each cover is read once. */
private val auraMemory = LruCache<String, Aura>(300)

/**
 * The colours of any cover, for tinting the page it heads. [picture] should be an address
 * the screen is showing anyway: the image then comes from memory, with no extra download.
 * Null until it has been read, and for a picture that cannot be loaded.
 */
@Composable
fun rememberCoverAura(picture: String): Aura? {
    val context = LocalContext.current
    var aura by remember(picture) { mutableStateOf(auraMemory.get(picture)) }
    LaunchedEffect(picture) {
        if (aura != null || picture.isEmpty()) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(picture).size(48).allowHardware(false).build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) { auraOf(image.toBitmap()) }
        auraMemory.put(picture, found)
        aura = found
    }
    return aura
}

/**
 * A soft band of a cover's colour behind the top of a page. Draws nothing until the colour
 * is known, then fades it in.
 */
@Composable
fun Modifier.coverGlow(picture: String): Modifier {
    val aura = rememberCoverAura(picture)
    val tint = animateColorAsState(aura?.deep ?: Color.Transparent, tween(600), label = "coverGlow")
    return drawBehind {
        val colour = tint.value
        if (colour.alpha > 0f) {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to colour.copy(alpha = colour.alpha * 0.85f), 1f to Color.Transparent))
        }
    }
}

/**
 * Picks the most characterful colour of a cover: a hue histogram weighted by
 * saturation, then fixed lightness so text on it always stays readable.
 */
internal fun auraOf(bitmap: Bitmap): Aura {
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
