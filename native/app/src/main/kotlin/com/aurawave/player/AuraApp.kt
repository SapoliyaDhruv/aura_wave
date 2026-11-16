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
import androidx.room.Room
import com.aurawave.innertube.Lrclib
import com.aurawave.player.data.Catalog
import com.aurawave.player.data.Library
import com.aurawave.player.data.ListenLog
import com.aurawave.player.data.LyricsStore
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
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** For work that must finish even if the screen or the player goes away (saving to the library). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tube = InnerTube(http)
    val catalog = Catalog(tube)
    val lyrics = LyricsStore(Lrclib(http), tube)

    private val database = Room.databaseBuilder(context, AuraDatabase::class.java, "aurawave.db").build()
    val library = Library(database.library(), scope)
    val listenLog = ListenLog(library, scope)

    /** Audio-only stream URLs, resolved on this device and reused until they expire. */
    val streamUrls = StreamUrlCache(StreamResolver(http))

    /** Audio that has been played is kept on disk, so replays cost no data. */
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    private companion object {
        const val MEDIA_CACHE_BYTES = 512L * 1024 * 1024
    }
}
