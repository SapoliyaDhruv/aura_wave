package com.aurawave.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The listener's preferences. Each one is a [Pref]: read it with `.value` or watch
 * it as a flow; writing it saves it straight away.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Keep playing similar songs when the queue runs out. */
    val autoplay = BoolPref("autoplay", true)

    /** When YouTube cannot play a song, look for the same recording on JioSaavn. */
    val saavnFallback = BoolPref("saavn_fallback", true)

    /** Look for a newer version of the app once a day, when it opens. */
    val autoUpdateCheck = BoolPref("auto_update_check", true)

    // ---------- look ----------

    /** The background the app is drawn on: the name of a Backdrop ("Dusk", "Black", "Slate"). */
    val themeBackdrop = StringPref("theme_backdrop", "Dusk")
    /** Where the accent colour comes from: "Cover" for the playing song's cover, or the name of a fixed colour. */
    val themeAccent = StringPref("theme_accent", "Cover")
    /** Swiping a song in a list queues it (right) or likes it (left). */
    val swipeSongs = BoolPref("swipe_songs", false)

    // ---------- the automatic backup ----------

    /** Keep a copy of the library in Documents/AuraWave, outside the app, so it outlives an uninstall. */
    val autoBackup = BoolPref("auto_backup", true)
    /** Where that copy is written: a file this install made, or the one picked when restoring. Empty until the first save. */
    val autoBackupUri = StringPref("auto_backup_uri", "")
    /** When it was last written, in milliseconds; 0 for never. */
    val autoBackupAt = LongPref("auto_backup_at", 0L)
    /** A fingerprint of what was last written, so an unchanged library is not written again. */
    val autoBackupHash = IntPref("auto_backup_hash", 0)
    /** The offer to restore from a backup has been made (or was not needed), on this install. */
    val restoreOffered = BoolPref("restore_offered", false)

    // ---------- where the listener left off ----------

    /** The collection the newest queue was started from, as [PlaySource.encode] wrote it; empty for none. */
    val lastPlayed = StringPref("last_played", "")
    /**
     * The songs of that collection, by id. Home offers play and pause only while the song on
     * is one of them, however the queue was changed since (a widget, Android Auto, autoplay).
     */
    val lastPlayedIds = ListPref("last_played_ids")

    // ---------- sound ----------

    val eqEnabled = BoolPref("eq_enabled", false)
    /** The device equaliser preset in use, or -1 for the listener's own band levels. */
    val eqPreset = IntPref("eq_preset", -1)
    /** The listener's own band levels in millibels, one per band. */
    val eqBands = ListPref("eq_bands")
    /** 0 to 1000. */
    val bassBoost = IntPref("bass_boost", 0)
    /** Even out loud and quiet songs using the loudness YouTube measured. */
    val levelling = BoolPref("levelling", true)
    val skipSilence = BoolPref("skip_silence", false)
    val speed = FloatPref("speed", 1f)
    val pitch = FloatPref("pitch", 1f)
    /** Podcasts have their own speed: people often listen to talk faster than to music. */
    val podcastSpeed = FloatPref("podcast_speed", 1f)
    /** Short fades when playing, pausing and skipping. */
    val fades = BoolPref("fades", true)
    /** Seconds two songs overlap; 0 plays them back to back (gapless). */
    val crossfadeSec = IntPref("crossfade_sec", 0)

    // ---------- data use ----------

    val downloadWifiOnly = BoolPref("download_wifi_only", true)
    val autoDownloadLiked = BoolPref("auto_download_liked", false)
    /** 0 means no limit. */
    val downloadLimitMb = IntPref("download_limit_mb", 0)
    val downloadQuality = StringPref("download_quality", "HIGH")

    val wifiQuality = StringPref("quality_wifi", "HIGH")
    val mobileQuality = StringPref("quality_mobile", "NORMAL")

    /** Artists and languages picked by the listener (imported from the old app), used by recommendations. */
    val tasteArtists = ListPref("taste_artists")
    val tasteLanguages = ListPref("taste_languages")

    abstract inner class Pref<T>(protected val key: String, initial: T) {
        private val state = MutableStateFlow(initial)
        val flow: StateFlow<T> = state.asStateFlow()
        var value: T
            get() = state.value
            set(v) {
                state.value = v
                prefs.edit().also { write(it, v) }.apply()
            }

        protected abstract fun write(editor: SharedPreferences.Editor, v: T)
    }

    inner class BoolPref(key: String, default: Boolean) : Pref<Boolean>(key, prefs.getBoolean(key, default)) {
        override fun write(editor: SharedPreferences.Editor, v: Boolean) {
            editor.putBoolean(key, v)
        }
    }

    inner class IntPref(key: String, default: Int) : Pref<Int>(key, prefs.getInt(key, default)) {
        override fun write(editor: SharedPreferences.Editor, v: Int) {
            editor.putInt(key, v)
        }
    }

    inner class LongPref(key: String, default: Long) : Pref<Long>(key, prefs.getLong(key, default)) {
        override fun write(editor: SharedPreferences.Editor, v: Long) {
            editor.putLong(key, v)
        }
    }

    inner class FloatPref(key: String, default: Float) : Pref<Float>(key, prefs.getFloat(key, default)) {
        override fun write(editor: SharedPreferences.Editor, v: Float) {
            editor.putFloat(key, v)
        }
    }

    inner class StringPref(key: String, default: String) : Pref<String>(key, prefs.getString(key, default) ?: default) {
        override fun write(editor: SharedPreferences.Editor, v: String) {
            editor.putString(key, v)
        }
    }

    /** A list of short strings, stored one per line. */
    inner class ListPref(key: String) :
        Pref<List<String>>(key, prefs.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()) {
        override fun write(editor: SharedPreferences.Editor, v: List<String>) {
            editor.putString(key, v.joinToString("\n") { it.replace('\n', ' ') })
        }
    }
}
