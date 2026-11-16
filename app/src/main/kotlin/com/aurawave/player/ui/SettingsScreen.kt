package com.aurawave.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.player.AppGraph
import com.aurawave.player.data.Backup
import com.aurawave.player.data.CrashLog
import com.aurawave.player.data.Release
import com.aurawave.player.data.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onSignIn: () -> Unit) {
    val actions = LocalSongActions.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = graph.settings
    val library = graph.library

    val autoplay by settings.autoplay.flow.collectAsStateWithLifecycle()
    val liked by library.likedIds.collectAsStateWithLifecycle()
    val playlists by library.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val plays by library.playCount.collectAsStateWithLifecycle(initialValue = 0)
    val hidden by library.hidden.collectAsStateWithLifecycle()
    val searches by library.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by graph.backup.progress.collectAsStateWithLifecycle()
    var confirmClearHistory by remember { mutableStateOf(false) }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                graph.backup.export(uri)
                actions.say("Backup saved")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.say("The backup could not be saved. Try another folder.")
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) graph.backup.restore(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = listEnd(24.dp))) {
            AccountSection(graph, onSignIn)

            SettingsGroup("Look and feel")
            LookSettings(graph)

            FoldedGroup("Tab order") { TabOrderSettings(settings) }

            SettingsGroup("Playback")
            SwitchSetting(
                title = "Autoplay",
                body = "Keep playing similar songs when the queue ends",
                checked = autoplay,
                onChange = { settings.autoplay.value = it },
            )

            val saavn by settings.saavnFallback.flow.collectAsStateWithLifecycle()
            SwitchSetting(
                title = "Try JioSaavn when YouTube can't play a song",
                body = "Looks for the same recording in JioSaavn's catalogue. It is not an official service for apps, so it may stop working.",
                checked = saavn,
                onChange = { settings.saavnFallback.value = it },
            )

            SettingsGroup("Streaming quality")
            Text(
                "Higher quality sounds better and uses more data. Songs already played or downloaded play from the phone either way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            QualitySetting("On Wi-Fi", settings.wifiQuality)
            QualitySetting("On mobile data", settings.mobileQuality)

            // Below: things set up once and then left alone. Each opens when its title is tapped.
            FoldedGroup("Downloads") {
                val wifiOnly by settings.downloadWifiOnly.flow.collectAsStateWithLifecycle()
                val autoLiked by settings.autoDownloadLiked.flow.collectAsStateWithLifecycle()
                val limitMb by settings.downloadLimitMb.flow.collectAsStateWithLifecycle()
                val used by graph.downloads.bytesUsed.collectAsStateWithLifecycle()
                var confirmRemoveAll by remember { mutableStateOf(false) }
                SwitchSetting(
                    title = "Download on Wi-Fi only",
                    body = "Downloads wait until the phone is on Wi-Fi",
                    checked = wifiOnly,
                    onChange = { settings.downloadWifiOnly.value = it },
                )
                SwitchSetting(
                    title = "Download liked songs",
                    body = "Every song you like is saved on the phone for offline play",
                    checked = autoLiked,
                    onChange = { settings.autoDownloadLiked.value = it },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Storage limit", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Using ${com.aurawave.player.playback.Downloads.formatSize(used)}" +
                            if (limitMb > 0) " of ${com.aurawave.player.playback.Downloads.formatSize(limitMb * 1024L * 1024)}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0 to "No limit", 1024 to "1 GB", 2048 to "2 GB", 5120 to "5 GB", 10240 to "10 GB").forEach { (mb, label) ->
                            FilterChip(
                                selected = limitMb == mb,
                                onClick = { settings.downloadLimitMb.value = mb },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Palette.OnAura),
                            )
                        }
                    }
                }
                ActionSetting(
                    title = "Remove all downloads",
                    body = if (used > 0) "Frees ${com.aurawave.player.playback.Downloads.formatSize(used)}" else "Nothing downloaded",
                    enabled = used > 0,
                    onClick = { confirmRemoveAll = true },
                )
                if (confirmRemoveAll) {
                    ConfirmDialog(
                        title = "Remove all downloads?",
                        body = "Every downloaded song is removed from this phone. Your library and playlists stay as they are.",
                        confirmLabel = "Remove all downloads",
                        onDismiss = { confirmRemoveAll = false },
                        onConfirm = {
                            confirmRemoveAll = false
                            graph.downloads.removeAll()
                            actions.say("Downloads removed")
                        },
                    )
                }
            }

            FoldedGroup("Your data") {
                Text(
                    "Everything is stored on this phone. Back it up before reinstalling the app or switching phones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ActionSetting(
                    title = "Back up library",
                    body = "${liked.size} liked, ${playlists.size} playlists, $plays plays",
                    onClick = { saveBackup.launch("aurawave-backup-${LocalDate.now()}.json") },
                )
                ActionSetting(
                    title = "Restore from a backup",
                    body = "Merges a backup into this library. Backups from the old AuraWave app work too.",
                    enabled = progress !is Backup.Progress.Working,
                    onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
                (progress as? Backup.Progress.Working)?.let { RestoreProgress(it) }
                ActionSetting(
                    title = "Clear listening history",
                    body = if (plays == 0) "No plays recorded" else "$plays plays recorded",
                    enabled = plays > 0,
                    onClick = { confirmClearHistory = true },
                )
            }

            FoldedGroup("Recommendations and search") {
                ActionSetting(
                    title = "Show hidden songs and artists again",
                    body = if (hidden.isEmpty()) "Nothing hidden" else "${hidden.size} kept out of recommendations",
                    enabled = hidden.isNotEmpty(),
                    onClick = { library.resetHidden(); actions.say("Hidden songs and artists can be recommended again") },
                )
                ActionSetting(
                    title = "Clear recent searches",
                    body = if (searches.isEmpty()) "No recent searches" else "${searches.size} saved",
                    enabled = searches.isNotEmpty(),
                    onClick = { library.clearSearches(); actions.say("Recent searches cleared") },
                )
            }

            FoldedGroup("App") {
                UpdateSettings(graph)
                CrashSetting(graph)
            }

            FoldedGroup("About") {
                Text(
                    "AuraWave ${graph.updates.installed}\nPlays audio from YouTube Music. Synced lyrics come from LRCLIB. " +
                        "Streams are found with NewPipeExtractor. AuraWave is free software under the GNU General Public " +
                        "License, version 3: you may share and change it under the same licence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = "Clear listening history?",
            body = "This also resets the play counts used for stats and recommendations. Liked songs and playlists are kept.",
            confirmLabel = "Clear history",
            onDismiss = { confirmClearHistory = false },
            onConfirm = {
                confirmClearHistory = false
                library.clearHistory()
                graph.recommender.invalidate()
                actions.say("Listening history cleared")
            },
        )
    }
    LaunchedEffect(progress) {
        // New history or likes change what is worth recommending.
        if (progress is Backup.Progress.Done) graph.recommender.invalidate()
    }
    when (val p = progress) {
        is Backup.Progress.Done -> RestoreReport(p.report, onDismiss = graph.backup::dismiss)
        is Backup.Progress.Failed -> AlertDialog(
            onDismissRequest = graph.backup::dismiss,
            containerColor = Palette.Dusk,
            title = { Text("Couldn't restore the backup") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = graph.backup::dismiss) { Text("Close") } },
        )
        else -> Unit
    }
}

/** "Check for updates", the automatic check, and the offer when a newer version is out. */
@Composable
private fun UpdateSettings(graph: AppGraph) {
    val update by graph.updates.state.collectAsStateWithLifecycle()
    val automatic by graph.settings.autoUpdateCheck.flow.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<Release?>(null) }
    val available = (update as? Updates.State.Available)?.release

    ActionSetting(
        title = if (available != null) "Get AuraWave ${available.version}" else "Check for updates",
        body = when (update) {
            Updates.State.Idle -> "You have version ${graph.updates.installed}"
            Updates.State.Checking -> "Checking…"
            Updates.State.UpToDate -> "Version ${graph.updates.installed} is the latest"
            is Updates.State.Available -> "A newer version than ${graph.updates.installed} is out"
            Updates.State.Failed -> "Couldn't check. Try again when you're online."
        },
        enabled = update != Updates.State.Checking,
        onClick = { if (available != null) offer = available else graph.updates.check() },
    )
    SwitchSetting(
        title = "Check for updates automatically",
        body = "Once a day, when the app opens. It asks GitHub for the latest version and sends nothing about you.",
        checked = automatic,
        onChange = { graph.settings.autoUpdateCheck.value = it },
    )
    offer?.let { UpdateDialog(graph, it, onDismiss = { offer = null }) }
}

/** Offers a newer version: what changed, and a button that downloads it. */
@Composable
fun UpdateDialog(graph: AppGraph, release: Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val actions = LocalSongActions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Dusk,
        title = { Text("AuraWave ${release.version} is available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (release.notes.isNotEmpty()) {
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "The download opens in your browser. Open the file when it finishes, and Android installs it over this " +
                        "version. Your library stays as it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!graph.updates.download(context, release)) actions.say("No browser could open the download.")
                onDismiss()
            }) { Text("Download update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Sends the record of the last crash to whoever looks after the app. */
@Composable
private fun CrashSetting(graph: AppGraph) {
    val context = LocalContext.current
    val actions = LocalSongActions.current
    var report by remember { mutableStateOf<CrashLog.Report?>(null) }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { graph.crashLog.last() } }
    val last = report
    ActionSetting(
        title = "Share the last crash report",
        body = if (last == null) {
            "The app has not crashed"
        } else {
            "From ${android.text.format.DateUtils.getRelativeTimeSpanString(last.at)}. It holds the error and your phone model, " +
                "nothing from your library or account."
        },
        enabled = last != null,
        onClick = { if (last != null && !graph.crashLog.share(context, last)) actions.say("No app could take the report.") },
    )
    if (last != null) {
        ActionSetting(
            title = "Delete the crash report",
            body = "",
            onClick = {
                graph.crashLog.clear()
                report = null
                actions.say("Crash report deleted")
            },
        )
    }
}

@Composable
private fun RestoreProgress(p: Backup.Progress.Working) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (p.total > 0) "${p.step}: ${p.done} of ${p.total}" else p.step,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        if (p.total > 0) {
            LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RestoreReport(report: Backup.Report, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Dusk,
        title = { Text("Backup restored") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val lines = buildList {
                    add("${report.liked} liked songs added")
                    add("${report.playlistsNew} new playlists" + if (report.playlistsUpdated > 0) ", ${report.playlistsUpdated} updated" else "")
                    if (report.plays > 0) add("${report.plays} plays added to your history")
                    addAll(report.notes)
                }
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (report.unmatched.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${report.unmatched.size} songs could not be found on YouTube Music and were left out:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    report.unmatched.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Restoring the same file again later retries them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AccountSection(graph: AppGraph, onSignIn: () -> Unit) {
    val account = graph.account
    val who by account.info.collectAsStateWithLifecycle()
    val sync by account.sync.collectAsStateWithLifecycle()
    val sendHistory by graph.settings.sendHistory.flow.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val actions = LocalSongActions.current

    SettingsGroup("YouTube Music account")
    val me = who
    if (me == null) {
        Text(
            "Optional. Signing in keeps your liked songs, playlists, albums and artists in step with YouTube Music. " +
                "Your login stays on this phone. YouTube's terms do not allow apps like this one to sign in, so use it at your own risk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ActionSetting(title = "Sign in", body = "Opens Google's sign-in page", onClick = onSignIn)
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(me.photoUrl, 40.dp, corner = 20.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(me.name.ifEmpty { "Signed in" }, style = MaterialTheme.typography.bodyLarge)
            if (me.handle.isNotEmpty()) Text(me.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val syncBody = when (val s = sync) {
        is com.aurawave.player.data.Account.Sync.Working -> "Syncing: ${s.step}…"
        is com.aurawave.player.data.Account.Sync.Failed -> s.message
        is com.aurawave.player.data.Account.Sync.Idle ->
            (if (s.lastSyncedAt == 0L) "Not synced yet" else "Last synced ${android.text.format.DateUtils.getRelativeTimeSpanString(s.lastSyncedAt)}") +
                if (s.note.isNotEmpty()) "\n${s.note}" else ""
    }
    ActionSetting(
        title = "Sync now",
        body = syncBody,
        enabled = sync !is com.aurawave.player.data.Account.Sync.Working,
        onClick = account::syncNow,
    )
    SwitchSetting(
        title = "Add plays to YouTube Music history",
        body = "So recommendations on YouTube Music learn from what you play here",
        checked = sendHistory,
        onChange = { graph.settings.sendHistory.value = it },
    )
    ActionSetting(title = "Sign out", body = "Your library on this phone stays as it is", onClick = { confirmSignOut = true })
    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out of YouTube Music?",
            body = "Changes stop syncing. Everything already in your library on this phone stays.",
            confirmLabel = "Sign out",
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                account.signOut()
                actions.say("Signed out")
            },
        )
    }
}

@Composable
private fun QualitySetting(title: String, pref: com.aurawave.player.data.Settings.StringPref) {
    val value by pref.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            QUALITIES.forEach { (name, label) ->
                FilterChip(
                    selected = value == name,
                    onClick = { pref.value = name },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Palette.OnAura),
                )
            }
        }
    }
}

/** The background, the accent colour and the swipe on songs. Each change shows at once, behind the page. */
@Composable
private fun LookSettings(graph: AppGraph) {
    val settings = graph.settings
    val backdrop by settings.themeBackdrop.flow.collectAsStateWithLifecycle()
    val accent by settings.themeAccent.flow.collectAsStateWithLifecycle()
    val swipe by settings.swipeSongs.flow.collectAsStateWithLifecycle()
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Palette.OnAura)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Background", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Backdrop.entries.forEach { choice ->
                FilterChip(
                    selected = backdrop == choice.name,
                    onClick = {
                        settings.themeBackdrop.value = choice.name
                        Palette.backdrop = choice
                    },
                    label = { Text(choice.label) },
                    colors = chipColors,
                )
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Accent colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "From the cover changes with each song; a fixed colour stays the same",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Accent.entries.forEach { choice ->
                FilterChip(
                    selected = accent == choice.name,
                    onClick = { settings.themeAccent.value = choice.name },
                    label = { Text(choice.label) },
                    leadingIcon = choice.aura?.let { aura ->
                        { Box(Modifier.size(14.dp).clip(CircleShape).background(aura.accent)) }
                    },
                    colors = chipColors,
                )
            }
        }
    }
    SwitchSetting(
        title = "Swipe on songs",
        body = "Swipe a song right to play it next, left to like it. Swiping sideways on a song then works the song instead of changing tabs.",
        checked = swipe,
        onChange = { settings.swipeSongs.value = it },
    )
}

/** Matches AudioQuality: about 70, 128 and 160 kbit/s. */
private val QUALITIES = listOf("HIGH" to "High", "NORMAL" to "Normal", "LOW" to "Data saver")

/** A group that is set up once and then left alone: closed until its title is tapped, so the page stays short. */
@Composable
private fun FoldedGroup(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "fold")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Close $title" else "Open $title") { open = !open }
            .padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Palette.Haze, modifier = Modifier.rotate(turn))
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
    ) {
        Column { content() }
    }
}

@Composable
fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchSetting(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, body, Modifier.weight(1f), enabled)
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Palette.OnAura, checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun ActionSetting(title: String, body: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, body, Modifier.weight(1f), enabled)
    }
}

@Composable
private fun SettingText(title: String, body: String, modifier: Modifier, enabled: Boolean) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (body.isNotEmpty()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
