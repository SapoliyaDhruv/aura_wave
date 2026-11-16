package com.aurawave.player.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.innertube.AlbumRef
import com.aurawave.innertube.Track
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection
import com.aurawave.player.playback.Sleep
import com.aurawave.player.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class OptionsPage { Main, Sleep, Sound, Speed, Alternatives }

/**
 * Everything in the player that is not playback itself, behind one button: sound,
 * speed, the sleep timer, choosing another recording, playlists and the artist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerOptionsSheet(graph: AppGraph, player: PlayerConnection, track: Track, onDismiss: () -> Unit, onLeavePlayer: () -> Unit) {
    var page by remember { mutableStateOf(OptionsPage.Main) }
    val sleepLabel = rememberSleepLabel(graph.sleepTimer)
    val isEpisode = track.kind == com.aurawave.innertube.Kind.EPISODE
    val musicSpeed by graph.settings.speed.flow.collectAsStateWithLifecycle()
    val podcastSpeed by graph.settings.podcastSpeed.flow.collectAsStateWithLifecycle()
    val speed = if (isEpisode) podcastSpeed else musicSpeed
    val musicPitch by graph.settings.pitch.flow.collectAsStateWithLifecycle()
    val pitch = if (isEpisode) 1f else musicPitch
    val eqOn by graph.settings.eqEnabled.flow.collectAsStateWithLifecycle()
    val crossfade by graph.settings.crossfadeSec.flow.collectAsStateWithLifecycle()
    val actions = LocalSongActions.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Dusk, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (page) {
                OptionsPage.Main -> {
                    val soundSummary = listOfNotNull(
                        "Equaliser".takeIf { eqOn },
                        "Crossfade ${crossfade}s".takeIf { crossfade > 0 },
                    ).joinToString(", ").ifEmpty { null }
                    val download = LocalDownloads.current[track.videoId]
                    OptionRow(
                        Icons.Rounded.Download,
                        when {
                            download?.done == true -> "Remove download"
                            download?.active == true -> "Downloading… (tap to cancel)"
                            else -> "Download"
                        },
                        null,
                    ) {
                        if (download?.done == true || download?.active == true) actions.removeDownload(track.videoId) else actions.download(listOf(track))
                        onDismiss()
                    }
                    OptionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", null) {
                        onDismiss()
                        actions.addToPlaylist(listOf(track))
                    }
                    track.leadArtist()?.let { artist ->
                        OptionRow(Icons.Rounded.Person, "Go to ${artist.name}", null) {
                            onDismiss()
                            onLeavePlayer()
                            actions.openArtist(artist)
                        }
                    }
                    if (track.albumId.isNotEmpty()) {
                        OptionRow(Icons.Rounded.Album, "Go to album", track.album.ifEmpty { null }) {
                            onDismiss()
                            onLeavePlayer()
                            actions.openAlbum(AlbumRef(track.albumId, track.album, track.artist, coverUrl = track.coverUrl))
                        }
                    }
                    // Below the line: things set once in a while rather than every day.
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Palette.Line)
                    OptionRow(Icons.Rounded.Bedtime, "Sleep timer", sleepLabel ?: "Off") { page = OptionsPage.Sleep }
                    OptionRow(Icons.Rounded.Equalizer, "Sound", soundSummary) { page = OptionsPage.Sound }
                    OptionRow(
                        Icons.Rounded.Speed,
                        if (isEpisode) "Podcast speed" else "Speed and pitch",
                        if (speed == 1f && pitch == 1f) "Normal" else "${formatRate(speed)}×" + if (pitch != 1f) ", pitch ${formatRate(pitch)}" else "",
                    ) { page = OptionsPage.Speed }
                    if (!isEpisode) OptionRow(Icons.Rounded.FindReplace, "Not the right song?", null) { page = OptionsPage.Alternatives }
                }
                OptionsPage.Sleep -> SleepPage(graph.sleepTimer, onBack = { page = OptionsPage.Main }, onDone = onDismiss)
                OptionsPage.Sound -> SoundPage(graph, onBack = { page = OptionsPage.Main })
                OptionsPage.Speed -> SpeedPage(graph, episode = track.kind == com.aurawave.innertube.Kind.EPISODE, onBack = { page = OptionsPage.Main })
                OptionsPage.Alternatives -> AlternativesPage(graph, player, track, onBack = { page = OptionsPage.Main }, onDone = onDismiss)
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to player options") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// ---------- sound ----------

@Composable
private fun SoundPage(graph: AppGraph, onBack: () -> Unit) {
    val settings = graph.settings
    val info by graph.sound.eqInfo.collectAsStateWithLifecycle()
    val levelDb by graph.sound.levelDb.collectAsStateWithLifecycle()
    val eqOn by settings.eqEnabled.flow.collectAsStateWithLifecycle()
    val preset by settings.eqPreset.flow.collectAsStateWithLifecycle()
    val bands by settings.eqBands.flow.collectAsStateWithLifecycle()
    val bass by settings.bassBoost.flow.collectAsStateWithLifecycle()
    val levelling by settings.levelling.flow.collectAsStateWithLifecycle()
    val skipSilence by settings.skipSilence.flow.collectAsStateWithLifecycle()
    val fades by settings.fades.flow.collectAsStateWithLifecycle()
    val crossfade by settings.crossfadeSec.flow.collectAsStateWithLifecycle()

    PageHeader("Sound", onBack)

    SwitchSetting(
        title = "Volume levelling",
        body = "Evens out loud and quiet songs, using the loudness YouTube measured" +
            (levelDb?.let { " (this song: %+.1f dB)".format(it) } ?: ""),
        checked = levelling,
        onChange = { settings.levelling.value = it },
    )
    SwitchSetting(
        title = "Fade on play, pause and skip",
        body = "No clicks when music starts or stops",
        checked = fades,
        onChange = { settings.fades.value = it },
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Crossfade", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (crossfade == 0) "Off: songs follow each other with no gap" else "Songs overlap by $crossfade seconds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var draft by remember(crossfade) { mutableFloatStateOf(crossfade.toFloat()) }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { settings.crossfadeSec.value = draft.roundToInt() },
            valueRange = 0f..12f,
            steps = 11,
        )
    }
    SwitchSetting(
        title = "Skip silence",
        body = "Leaves out silent intros and gaps",
        checked = skipSilence,
        onChange = { settings.skipSilence.value = it },
    )

    PhoneSoundEffects(graph)

    val eq = info
    if (eq != null && (eq.bandsHz.isNotEmpty() || eq.hasBassBoost)) {
        SwitchSetting(
            title = "Equaliser",
            body = "Uses your phone's built-in equaliser",
            checked = eqOn,
            onChange = { settings.eqEnabled.value = it },
        )
        if (eqOn) {
            if (eq.presets.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EqChip("Custom", preset < 0) {
                        // Start the custom curve from the preset in use, so the sliders do not jump.
                        val from = eq.presetLevels.getOrNull(preset)
                        if (from != null) settings.eqBands.value = from.map { it.toString() }
                        settings.eqPreset.value = -1
                    }
                    eq.presets.forEachIndexed { i, name -> EqChip(name, preset == i) { settings.eqPreset.value = i } }
                }
            }
            val levels = if (preset >= 0) eq.presetLevels.getOrNull(preset).orEmpty() else bands.mapNotNull { it.toIntOrNull() }
            eq.bandsHz.forEachIndexed { b, hz ->
                val level = levels.getOrElse(b) { 0 }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatHz(hz), style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 56.dp))
                    var draft by remember(level, preset) { mutableFloatStateOf(level.toFloat()) }
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        onValueChangeFinished = {
                            val current = eq.bandsHz.indices.map { i -> levels.getOrElse(i) { 0 } }.toMutableList()
                            current[b] = draft.roundToInt()
                            settings.eqBands.value = current.map { it.toString() }
                            settings.eqPreset.value = -1
                        },
                        valueRange = eq.minMb.toFloat()..eq.maxMb.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text("%+.0f dB".format(draft / 100f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 52.dp))
                }
            }
            if (eq.hasBassBoost) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bass", style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 56.dp))
                    var draft by remember(bass) { mutableFloatStateOf(bass.toFloat()) }
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        onValueChangeFinished = { settings.bassBoost.value = draft.roundToInt() },
                        valueRange = 0f..1000f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(draft / 10).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 52.dp))
                }
            }
        }
    } else if (eq != null) {
        Hint("This phone has no built-in equaliser that apps can use.")
    }
}

/**
 * Opens the phone's own sound panel for this app's music: Dolby Atmos, the maker's equaliser
 * and whatever else the phone has. Those effects belong to the phone, so the app can only
 * open the door to them. Shown only on phones that have such a panel.
 */
@Composable
private fun PhoneSoundEffects(graph: AppGraph) {
    val context = LocalContext.current
    val session by graph.sound.audioSession.collectAsStateWithLifecycle()
    val intent = remember(session) {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            .apply { if (session > 0) putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session) }
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    if (!available) return
    // The panel must be opened "for a result", though it has none to give.
    val panel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    ActionSetting(
        title = "Phone sound effects",
        body = "Dolby Atmos and the other effects built into your phone",
        onClick = { runCatching { panel.launch(intent) } },
    )
}

@Composable
private fun EqChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Palette.OnAura),
    )
}

private fun formatHz(hz: Int): String = if (hz >= 1000) "${"%.1f".format(hz / 1000f).removeSuffix(".0")} kHz" else "$hz Hz"

// ---------- speed ----------

@Composable
private fun SpeedPage(graph: AppGraph, episode: Boolean, onBack: () -> Unit) {
    val settings = graph.settings
    val speedPref = if (episode) settings.podcastSpeed else settings.speed
    val speed by speedPref.flow.collectAsStateWithLifecycle()
    val pitch by settings.pitch.flow.collectAsStateWithLifecycle()
    PageHeader(if (episode) "Podcast speed" else "Speed and pitch", onBack) {
        if (speed != 1f || (!episode && pitch != 1f)) {
            TextButton(onClick = { speedPref.value = 1f; if (!episode) settings.pitch.value = 1f }) { Text("Reset") }
        }
    }
    Hint(if (episode) "Applies to every podcast episode. Songs keep their own speed." else "Applies to every song until you change it back. Podcasts have their own speed.")
    RateSlider("Speed", speed, 0.5f..3f) { speedPref.value = it }
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v -> EqChip("${formatRate(v)}×", speed == v) { speedPref.value = v } }
    }
    if (!episode) {
        Spacer(Modifier.height(12.dp))
        RateSlider("Pitch", pitch, 0.5f..1.5f) { settings.pitch.value = it }
    }
}

@Composable
private fun RateSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onSet: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$label: ${formatRate(draft)}×", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = draft,
            onValueChange = { draft = (it * 20).roundToInt() / 20f }, // steps of 0.05
            onValueChangeFinished = { onSet(draft) },
            valueRange = range,
        )
    }
}

private fun formatRate(v: Float): String = "%.2f".format(v).trimEnd('0').trimEnd('.')

// ---------- not the right song ----------

@Composable
private fun AlternativesPage(graph: AppGraph, player: PlayerConnection, track: Track, onBack: () -> Unit, onDone: () -> Unit) {
    val loader = rememberLoader(track.videoId, "No other recordings of this song were found.") {
        graph.catalog.alternatives(track).ifEmpty { null }
    }
    val scope = rememberCoroutineScope()
    val actions = LocalSongActions.current
    PageHeader("Not the right song?", onBack)
    Hint("Pick the recording you meant. AuraWave remembers your choice for this song, in your likes and playlists too.")
    when (val s = loader.state) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(s.message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = loader.retry) { Text("Try again") }
        }
        is Load.Ready -> s.value.forEach { alt ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        graph.library.pin(track, alt)
                        player.replaceCurrent(alt)
                        actions.say("Playing ${alt.title} by ${alt.artist}")
                        onDone()
                    }
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(alt.coverUrl, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(alt.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(alt.artist, alt.album, if (alt.durationSec > 0) formatTime(alt.durationSec * 1000L) else "", kindLabel(alt))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
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

private fun kindLabel(t: Track): String = when (t.kind) {
    com.aurawave.innertube.Kind.SONG -> ""
    com.aurawave.innertube.Kind.VIDEO -> "Music video"
    com.aurawave.innertube.Kind.UPLOAD -> "Upload"
    com.aurawave.innertube.Kind.EPISODE -> "Episode"
}

// ---------- sleep timer ----------

@Composable
private fun SleepPage(timer: SleepTimer, onBack: () -> Unit, onDone: () -> Unit) {
    val sleep by timer.sleep.collectAsStateWithLifecycle()
    var songs by remember { mutableIntStateOf(3) }
    val actions = LocalSongActions.current
    PageHeader("Sleep timer", onBack) {
        if (sleep != null) {
            TextButton(onClick = { timer.cancel(); actions.say("Sleep timer off"); onDone() }) { Text("Turn off") }
        }
    }
    rememberSleepLabel(timer)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
    }
    Hint("The music fades out over the last 30 seconds.")
    Spacer(Modifier.height(8.dp))
    listOf(15, 30, 45, 60, 90, 120).chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { minutes ->
                OutlinedButton(
                    onClick = {
                        timer.inMinutes(minutes)
                        actions.say("Music stops in ${minutesLabel(minutes)}")
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(minutesLabel(minutes)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OptionRow(null, "At the end of this song or episode", null) {
        timer.endOfSong()
        actions.say("Music stops after this song")
        onDone()
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("After", style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { songs = (songs - 1).coerceAtLeast(2) }, enabled = songs > 2) {
            Icon(Icons.Rounded.Remove, contentDescription = "Fewer songs")
        }
        Text("$songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { songs = (songs + 1).coerceAtMost(20) }, enabled = songs < 20) {
            Icon(Icons.Rounded.Add, contentDescription = "More songs")
        }
        Text("songs", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = {
            timer.afterSongs(songs)
            actions.say("Music stops after $songs songs")
            onDone()
        }) { Text("Set") }
    }
}

@Composable
private fun OptionRow(icon: ImageVector?, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
}

private fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60}.${(minutes % 60) * 10 / 60} hr"
}

/** "Stops in 12:30", "Stops after this song", or null when the timer is off. */
fun sleepLabel(sleep: Sleep?): String? = when (sleep) {
    null -> null
    is Sleep.At -> "Stops in ${formatTime(sleep.remainingMs())}"
    Sleep.EndOfSong -> "Stops after this song"
    is Sleep.AfterSongs -> "Stops after ${sleep.songs} songs"
}

/** The sleep timer's label, ticking once a second while a countdown runs. */
@Composable
fun rememberSleepLabel(timer: SleepTimer): String? {
    val sleep by timer.sleep.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleep) {
        while (sleep is Sleep.At) {
            delay(1000)
            tick++
        }
    }
    return remember(sleep, tick) { sleepLabel(sleep) }
}
