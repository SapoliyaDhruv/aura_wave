package com.aurawave.player.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps what went wrong the last time the app crashed, so it can be sent to whoever
 * maintains the app. There is no Play Store to collect crashes for a hand-installed app.
 *
 * The report holds the error, the app version and the phone model. It holds nothing from
 * the listener's library or account, and it leaves the phone only when they share it.
 */
class CrashLog(private val context: Context) {
    class Report(val at: Long, val text: String)

    private val file = File(context.filesDir, "last_crash.txt")

    /** Starts recording crashes. The crash itself still happens as usual afterwards. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): Report? = runCatching {
        if (file.exists()) Report(file.lastModified(), file.readText()) else null
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    /** Offers the report to the phone's apps (messages, mail). Returns false when nothing could take it. */
    fun share(from: Context, report: Report): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "AuraWave crash report")
            .putExtra(Intent.EXTRA_TEXT, report.text)
        from.startActivity(Intent.createChooser(send, "Send the crash report"))
    }.isSuccess

    private fun describe(thread: Thread, error: Throwable): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})" }
        }.getOrDefault("unknown")
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("AuraWave $version")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("${Instant.now()}, thread ${thread.name}")
            appendLine()
            append(trace)
        }.take(MAX_CHARS) // an intent can carry only so much text
    }

    private companion object {
        const val MAX_CHARS = 60_000
    }
}
