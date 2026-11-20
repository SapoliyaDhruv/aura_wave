package com.aurawave.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurawave.player.AppGraph
import com.aurawave.player.data.Backup

/**
 * The first time a fresh install opens with nothing in its library: if AuraWave was here
 * before and its data was not kept at uninstall, its automatic backup is still in
 * Documents/AuraWave. Android does not let a new install read that file by itself, so the
 * listener is asked to pick it, with the file picker opened on that folder.
 */
@Composable
fun WelcomeBack(graph: AppGraph) {
    val settings = graph.settings
    var asking by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val progress by graph.backup.progress.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (settings.restoreOffered.value) return@LaunchedEffect
        if (graph.library.isEmpty()) asking = true else settings.restoreOffered.value = true
    }

    val pick = rememberLauncherForActivityResult(OpenBackup(graph.backup.autoFolderUri)) { uri ->
        // Closing the picker without a file leaves the question open.
        if (uri == null) return@rememberLauncherForActivityResult
        asking = false
        restoring = true
        settings.restoreOffered.value = true
        graph.backup.restore(uri, fromAutoCopy = true)
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            containerColor = Palette.Dusk,
            title = { Text("Used AuraWave before?") },
            text = {
                Text(
                    "AuraWave keeps a backup of your library in Documents/AuraWave. " +
                        "Pick \"AuraWave backup\" there to bring back your liked songs, playlists and history.",
                )
            },
            confirmButton = { TextButton(onClick = { pick.launch(BACKUP_TYPES) }) { Text("Restore my library") } },
            dismissButton = {
                TextButton(onClick = { asking = false; settings.restoreOffered.value = true }) { Text("Start fresh") }
            },
        )
    }

    // The settings page shows a restore's progress itself; one started here gets a dialog.
    val working = progress as? Backup.Progress.Working
    if (restoring && working != null) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            containerColor = Palette.Dusk,
            title = { Text("Restoring your library") },
            text = { RestoreProgress(working) },
            confirmButton = {},
        )
    }
    if (restoring && progress !is Backup.Progress.Working) {
        LaunchedEffect(progress) { if (progress !is Backup.Progress.Idle) restoring = false }
    }
}

/** The system file picker, opened on the folder the automatic backup is kept in. */
private class OpenBackup(private val folder: Uri) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}
