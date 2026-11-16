package com.aurawave.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** Something a screen fetches from YouTube Music. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

/** Runs [load] when [key] changes (or on retry). A null result is shown as [emptyMessage]. */
@Composable
fun <T : Any> rememberLoader(key: Any?, emptyMessage: String, load: suspend () -> T?): Loader<T> {
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = try {
            load()?.let { Load.Ready(it) } ?: Load.Failed(emptyMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }
    return Loader(state) { attempt++ }
}

/** Shows the loading spinner or the failure (with Try again), and [content] once loaded. */
@Composable
fun <T> LoadContent(loader: Loader<T>, failedTitle: String, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> MessageBox(title = failedTitle, body = s.message, action = "Try again", onAction = loader.retry)
        is Load.Ready -> content(s.value)
    }
}
