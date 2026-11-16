package com.aurawave.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A published version of the app: its number, what changed, and where to get it. */
data class Release(val version: String, val notes: String, val downloadUrl: String)

/**
 * Looks for a newer AuraWave among the project's releases on GitHub. The app is installed
 * by hand, so this is how a listener learns that a new version exists. It is one plain
 * request for a public page and sends nothing about the listener or their library.
 *
 * Getting the update opens the download in the browser; Android then asks before installing.
 */
class Updates(
    context: Context,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data object Failed : State
    }

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** The version running now, like "3.0.0". */
    val installed: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private val current = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = current.asStateFlow()

    /** "Check for updates" in Settings. */
    fun check() {
        if (current.value == State.Checking) return
        current.value = State.Checking
        scope.launch { current.value = look() }
    }

    /**
     * Called when the app opens. Looks at most once a day, and only when the listener left
     * automatic checks on. Returns a release when there is a newer one to offer.
     */
    suspend fun checkIfDue(): Release? {
        if (!settings.autoUpdateCheck.value) return null
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS) return null
        val found = look()
        // A failed look (no connection) is tried again next time; anything else waits a day.
        if (found != State.Failed) prefs.edit().putLong(LAST_CHECK, now).apply()
        if (found is State.Available) current.value = found
        return (found as? State.Available)?.release
    }

    private suspend fun look(): State = try {
        val release = latest()
        if (release != null && isNewerVersion(release.version, installed)) State.Available(release) else State.UpToDate
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        State.Failed
    }

    /** The newest published release, or null when none has been published yet. */
    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            response.body.string()
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val tag = root.text("tag_name") ?: return@withContext null
        val apk = (root["assets"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("browser_download_url") }
            .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
        val page = root.text("html_url") ?: "https://github.com/$REPOSITORY/releases"
        Release(version = tag.trim().removePrefix("v").removePrefix("V"), notes = root.text("body").orEmpty().trim(), downloadUrl = apk ?: page)
    }

    /** Opens the download (or the release page, when the release has no APK attached) in the browser. */
    fun download(context: Context, release: Release): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        /** Where versions are published: a release tagged like "v3.0.1" with the APK attached. */
        const val REPOSITORY = "SapoliyaDhruv/aura_wave"
        const val LAST_CHECK = "last_check"
        const val CHECK_EVERY_MS = 24 * 3_600_000L
    }
}

/**
 * Whether [candidate] ("v3.1.0", "3.0.1") is a later version than [installed]. Numbers are
 * compared part by part; with equal numbers, a finished version is later than a preview
 * of it ("3.0.0" is newer than "3.0.0-beta1").
 */
internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    fun clean(v: String) = v.trim().removePrefix("v").removePrefix("V")
    fun numbers(v: String) = clean(v).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = numbers(candidate)
    val b = numbers(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return '-' in clean(installed) && '-' !in clean(candidate)
}
