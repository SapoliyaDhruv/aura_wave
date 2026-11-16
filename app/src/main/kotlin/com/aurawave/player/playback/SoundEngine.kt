package com.aurawave.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.aurawave.innertube.Loudness
import com.aurawave.player.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the phone's equaliser offers, for the Sound sheet. Null bands mean the phone has no equaliser. */
data class EqInfo(
    val bandsHz: List<Int>,
    val minMb: Int,
    val maxMb: Int,
    val presets: List<String>,
    /** Band levels of each preset, so choosing one moves the sliders. */
    val presetLevels: List<List<Int>>,
    val hasBassBoost: Boolean,
)

/** Shared between the playback service, which applies sound settings, and the Sound sheet, which shows them. */
class SoundState {
    val eqInfo = MutableStateFlow<EqInfo?>(null)

    /** The levelling applied to the playing song, in dB, for the Sound sheet to show. */
    val levelDb = MutableStateFlow<Double?>(null)

    /** The player's audio session (0 until it has one), so the phone's own sound effects can be opened for it. */
    val audioSession = MutableStateFlow(0)
}

/**
 * Applies the listener's sound settings to the player: the phone's equaliser and bass
 * boost, speed and pitch, skipping silence, and volume levelling.
 *
 * Several things want to change the volume at once (levelling a loud song down, fading
 * on pause, the sleep timer fading out, a crossfade), so each sets its own factor here
 * and the player's volume is their product.
 */
@OptIn(UnstableApi::class)
class SoundEngine(
    private val player: ExoPlayer,
    private val settings: Settings,
    private val loudness: Loudness,
    private val state: SoundState,
    private val scope: CoroutineScope,
) {
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var enhancer: LoudnessEnhancer? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET

    private var level = 1f
    private var fade = 1f
    private var sleep = 1f
    private var cross = 1f
    private var fadeJob: Job? = null
    private var levelJob: Job? = null

    private val gains = ConcurrentHashMap<String, Double>()
    private val episode = MutableStateFlow(false)

    /** Podcast episodes play at the podcast speed. */
    fun playingEpisode(isEpisode: Boolean) {
        episode.value = isEpisode
    }

    fun start() {
        attach(player.audioSessionId)
        scope.launch {
            combine(settings.eqEnabled.flow, settings.eqPreset.flow, settings.eqBands.flow, settings.bassBoost.flow) { _, _, _, _ -> }
                .collect { applyEffects() }
        }
        scope.launch {
            combine(settings.speed.flow, settings.podcastSpeed.flow, settings.pitch.flow, episode) { music, talk, pitch, isEpisode ->
                // Episodes keep their natural pitch: a changed voice is tiring to listen to.
                if (isEpisode) PlaybackParameters(talk, 1f) else PlaybackParameters(music, pitch)
            }.collect { player.playbackParameters = it }
        }
        scope.launch { settings.skipSilence.flow.collect { player.skipSilenceEnabled = it } }
        scope.launch { settings.levelling.flow.collect { levelFor(player.currentMediaItem?.streamId) } }
    }

    /** The player's audio session changed (it can when the audio output is rebuilt). */
    fun attach(newSession: Int) {
        if (newSession == sessionId || newSession == C.AUDIO_SESSION_ID_UNSET) return
        releaseEffects()
        sessionId = newSession
        state.audioSession.value = newSession
        equalizer = runCatching { Equalizer(0, newSession) }.getOrNull()
        bass = runCatching { BassBoost(0, newSession) }.getOrNull()?.takeIf { it.strengthSupported }
        enhancer = runCatching { LoudnessEnhancer(newSession) }.getOrNull()
        state.eqInfo.value = equalizer?.let { eq ->
            runCatching {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
                // Reading a preset's levels means switching to it; the listener's settings are applied right after.
                val levels = presets.indices.map { p ->
                    eq.usePreset(p.toShort())
                    (0 until bands).map { b -> eq.getBandLevel(b.toShort()).toInt() }
                }
                EqInfo(
                    bandsHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                    minMb = range[0].toInt(),
                    maxMb = range[1].toInt(),
                    presets = presets,
                    presetLevels = levels,
                    hasBassBoost = bass != null,
                )
            }.getOrNull()
        } ?: EqInfo(emptyList(), 0, 0, emptyList(), emptyList(), bass != null)
        applyEffects()
        applyEnhancer(currentBoostDb)
    }

    private fun applyEffects() {
        val enabled = settings.eqEnabled.value
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = enabled
                if (enabled) {
                    val preset = settings.eqPreset.value
                    if (preset >= 0 && preset < eq.numberOfPresets) {
                        eq.usePreset(preset.toShort())
                    } else {
                        val levels = settings.eqBands.value.mapNotNull { it.toIntOrNull() }
                        val range = eq.bandLevelRange
                        for (b in 0 until eq.numberOfBands) {
                            val mb = levels.getOrElse(b) { 0 }.coerceIn(range[0].toInt(), range[1].toInt())
                            eq.setBandLevel(b.toShort(), mb.toShort())
                        }
                    }
                }
            }
        }
        bass?.let { b ->
            runCatching {
                val strength = settings.bassBoost.value.coerceIn(0, 1000)
                b.enabled = enabled && strength > 0
                if (b.enabled) b.setStrength(strength.toShort())
            }
        }
    }

    // ---------- volume ----------

    private var currentBoostDb = 0.0

    /** Looks up the new song's loudness and levels it. */
    fun levelFor(videoId: String?) {
        levelJob?.cancel()
        if (videoId == null || !settings.levelling.value) {
            setLevel(0.0)
            state.levelDb.value = null
            return
        }
        gains[videoId]?.let {
            setLevel(it)
            return
        }
        levelJob = scope.launch {
            val gain = loudness.gainDb(videoId) ?: 0.0
            gains[videoId] = gain
            if (player.currentMediaItem?.streamId == videoId) setLevel(gain)
        }
    }

    /** Turning down uses the player's volume; turning up needs the loudness enhancer, which only boosts. */
    private fun setLevel(db: Double) {
        state.levelDb.value = db.takeIf { settings.levelling.value }
        level = if (db < 0) 10.0.pow(db / 20).toFloat() else 1f
        currentBoostDb = if (db > 0) db else 0.0
        applyEnhancer(currentBoostDb)
        push()
    }

    private fun applyEnhancer(db: Double) {
        enhancer?.let { e ->
            runCatching {
                e.setTargetGain((db * 100).roundToInt())
                e.enabled = db > 0
            }
        }
    }

    fun setSleepFactor(f: Float) {
        sleep = f.coerceIn(0f, 1f)
        push()
    }

    /** The incoming song's share of a crossfade. */
    fun setCrossfade(f: Float) {
        cross = f.coerceIn(0f, 1f)
        push()
    }

    private var tail: ExoPlayer? = null
    private var tailShare = 1f

    /** The outgoing song of a crossfade: it follows pause fades and the sleep timer like the main song. */
    fun setTail(player: ExoPlayer?, share: Float = 1f) {
        tail = player
        tailShare = share.coerceIn(0f, 1f)
        push()
    }

    fun setFade(f: Float) {
        fadeJob?.cancel()
        fade = f.coerceIn(0f, 1f)
        push()
    }

    /** Ramps the fade factor to [target] over [ms], then runs [then]. */
    fun fadeTo(target: Float, ms: Long, then: () -> Unit = {}) {
        fadeJob?.cancel()
        val from = fade
        fadeJob = scope.launch {
            val steps = (ms / STEP_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                delay(STEP_MS)
                fade = from + (target - from) * i / steps
                push()
            }
            fade = target
            push()
            then()
        }
    }

    fun cancelFade() {
        fadeJob?.cancel()
    }

    private fun push() {
        player.volume = (level * fade * sleep * cross).coerceIn(0f, 1f)
        tail?.volume = (level * fade * sleep * tailShare).coerceIn(0f, 1f)
    }

    fun release() {
        levelJob?.cancel()
        fadeJob?.cancel()
        releaseEffects()
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { enhancer?.release() }
        equalizer = null
        bass = null
        enhancer = null
    }

    private companion object {
        const val STEP_MS = 25L
    }
}
