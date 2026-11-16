package com.aurawave.player.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurawave.innertube.Track

/**
 * Downloads every song of an album or playlist. Shows progress while they download, and
 * a tick when all are on the phone (tapping it offers to remove them).
 */
@Composable
fun DownloadAllButton(tracks: List<Track>, name: String) {
    if (tracks.isEmpty()) return
    val downloads = LocalDownloads.current
    val actions = LocalSongActions.current
    var confirmRemove by remember { mutableStateOf(false) }
    val states = tracks.map { downloads[it.videoId] }
    val done = states.count { it?.done == true }
    val active = states.any { it?.active == true }
    when {
        done == tracks.size -> IconButton(onClick = { confirmRemove = true }) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded. Remove the download", tint = MaterialTheme.colorScheme.primary)
        }
        active -> IconButton(onClick = {}) {
            CircularProgressIndicator(
                progress = { done.toFloat() / tracks.size },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
            )
        }
        else -> IconButton(onClick = { actions.download(tracks) }) {
            Icon(Icons.Rounded.Download, contentDescription = "Download all ${tracks.size} songs")
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove the download?",
            body = "The ${songCount(tracks.size)} of $name are removed from this phone. They still play when you're online.",
            confirmLabel = "Remove download",
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                tracks.forEach { actions.removeDownload(it.videoId) }
            },
        )
    }
}
