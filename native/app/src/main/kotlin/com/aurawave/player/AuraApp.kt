package com.aurawave.player

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.aurawave.innertube.InnerTube
import com.aurawave.innertube.StreamResolver
import com.aurawave.innertube.AudioQuality
import com.aurawave.innertube.Loudness
import com.aurawave.player.playback.SoundState
import com.aurawave.player.playback.Downloads
import android.net.ConnectivityManager
import androidx.room.Room
import com.aurawave.innertube.Lrclib
import com.aurawave.player.data.Catalog
import com.aurawave.player.data.CrashLog
import com.aurawave.player.data.Updates
import com.aurawave.player.data.Library
import com.aurawave.player.data.ListenLog
import com.aurawave.player.data.LyricsStore
import com.aurawave.player.data.Settings
import com.aurawave.player.data.Backup
import com.aurawave.player.data.Account
import com.aurawave.player.data.PlaylistImporter
import com.aurawave.player.data.Recommender
import com.aurawave.innertube.Spotify
import com.aurawave.player.playback.QueueStore
import com.aurawave.player.playback.SleepTimer
import com.aurawave.player.data.db.AuraDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.aurawave.player.playback.StreamUrlCache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AuraApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app's long-lived objects, shared by the screens and the playback service. */
@OptIn(UnstableApi::class)
class AppGraph(context: Context) {
    /** First, so a crash while the rest of the app is being set up is recorded too. */
    val crashLog = CrashLog(context).also { it.install() }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** For work that must finish even if the screen or the player goes away (saving to the library). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = Settings(context)

    private val tube = InnerTube(http)
    val catalog = Catalog(tube)
    val lyrics = LyricsStore(Lrclib(http), tube)

    private val database = Room.databaseBuilder(context, AuraDatabase::class.java, "aurawave.db").build()
    val library = Library(database.library(), scope)
    val listenLog = ListenLog(library, scope)
    val backup = Backup(context, library, catalog, settings, scope)
    val importer = PlaylistImporter(library, catalog, Spotify(http), scope)
    val recommender = Recommender(context, library, catalog, settings, scope)
    val account = Account(context, tube, library, settings, scope)
    val updates = Updates(context, http, settings, scope)

    val sleepTimer = SleepTimer()
    val queueStore = QueueStore(context.filesDir)

    /** Audio-only stream URLs, resolved on this device and reused until they expire. */
    private val resolver = StreamResolver(http)
    val streamUrls = StreamUrlCache(resolver) { qualityNow(context) }

    /** A video-only stream for the Song/Video switch: up to 720p on Wi-Fi, 480p on mobile data. */
    suspend fun videoUrl(videoId: String): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val metered = appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        runCatching { resolver.videoUrls(videoId, if (metered) 480 else 720).firstOrNull()?.second }.getOrNull()
    }

    private val appContext = context.applicationContext

    val loudness = Loudness(http)
    val sound = SoundState()

    /** The quality the listener chose for the network the phone is on now. */
    private fun qualityNow(context: Context): AudioQuality {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val name = if (cm?.isActiveNetworkMetered != false) settings.mobileQuality.value else settings.wifiQuality.value
        return runCatching { AudioQuality.valueOf(name) }.getOrDefault(AudioQuality.HIGH)
    }

    /** Media3's own bookkeeping (cache index, download list): one database for both. */
    private val exoDatabase = StandaloneDatabaseProvider(context)

    /** Audio that has been played is kept on disk, so replays cost no data. */
    val mediaCache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES), exoDatabase)
    }

    /** Songs saved for offline play. Created on first use, on the main thread (its manager needs a looper). */
    val downloads: Downloads by lazy { Downloads(context, exoDatabase, http, resolver, settings, library, scope) }

    private companion object {
        const val MEDIA_CACHE_BYTES = 512L * 1024 * 1024
    }
}
