package com.aurawave.player.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurawave.innertube.Kind
import com.aurawave.innertube.Track
import com.aurawave.player.AuraApp
import com.aurawave.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The square widget: the playing song's cover, large, with its name over the lower edge
 * and a play/pause button. Drawn from the same state as [PlayerWidget].
 */
class CoverWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, PlayerWidget.last)
    }

    companion object {
        fun render(context: Context, state: PlayerWidget.State?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CoverWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_cover)
            if (state != null) {
                views.setTextViewText(R.id.widget_title, state.title)
                views.setTextViewText(R.id.widget_artist, state.artist)
                views.setImageViewResource(R.id.widget_play_icon, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                views.setContentDescription(R.id.widget_play, if (state.playing) "Pause" else "Play")
                if (state.cover != null) {
                    views.setImageViewBitmap(R.id.widget_cover, state.cover)
                } else {
                    views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                }
                views.setInt(R.id.widget_bg, "setColorFilter", state.tint)
                views.setInt(R.id.widget_play_bg, "setColorFilter", state.accent)
            }
            views.setOnClickPendingIntent(R.id.widget_root, PlayerWidget.openApp(context))
            views.setOnClickPendingIntent(R.id.widget_play, PlayerWidget.playPause(context))
            manager.updateAppWidget(ids, views)
        }
    }
}

/**
 * The "recently played" widget: the last four songs as covers. Tapping one plays it, with
 * the other recent songs queued after it, whether or not the app is open.
 */
class RecentWidget : AppWidgetProvider() {
    /** The widget was just added, or the phone restarted: draw it, keeping the app alive until it is drawn. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, goAsync())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PLAY) {
            play(context.applicationContext, intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty(), goAsync())
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_PLAY = "com.aurawave.player.widget.PLAY_RECENT"
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val COVER_PX = 220
        private const val QUEUE_LENGTH = 20
        private const val RELEASE_DELAY_MS = 2_000L
        private const val GIVE_UP_MS = 8_000L
        private val SLOTS = intArrayOf(R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Redraws the widget from the listening history. Does nothing when the widget is not on the home screen. */
        fun refresh(context: Context, pending: PendingResult? = null) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            if (manager.getAppWidgetIds(ComponentName(app, RecentWidget::class.java)).isEmpty()) {
                pending?.finish()
                return
            }
            scope.launch {
                try {
                    withTimeoutOrNull(GIVE_UP_MS) { draw(app) }
                } catch (_: Exception) {
                    // The history could not be read just now; the next song change draws it.
                } finally {
                    pending?.finish()
                }
            }
        }

        private suspend fun recentSongs(app: Context, count: Int): List<Track> =
            (app as AuraApp).graph.library.recentlyPlayed.first().filter { it.kind != Kind.EPISODE }.take(count)

        private suspend fun draw(app: Context) {
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, RecentWidget::class.java))
            if (ids.isEmpty()) return
            val songs = recentSongs(app, SLOTS.size)
            val covers = songs.map { PlayerWidget.roundedCover(app, it.coverUrl, COVER_PX) }
            val views = RemoteViews(app.packageName, R.layout.widget_recent)
            SLOTS.forEachIndexed { i, slot ->
                val song = songs.getOrNull(i)
                if (song == null) {
                    views.setViewVisibility(slot, View.INVISIBLE)
                    return@forEachIndexed
                }
                views.setViewVisibility(slot, View.VISIBLE)
                val cover = covers[i]
                if (cover != null) views.setImageViewBitmap(slot, cover) else views.setImageViewResource(slot, R.drawable.widget_cover_placeholder)
                views.setContentDescription(slot, "Play ${song.title}")
                val play = Intent(app, RecentWidget::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_VIDEO_ID, song.videoId)
                views.setOnClickPendingIntent(slot, PendingIntent.getBroadcast(app, i, play, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            views.setViewVisibility(R.id.recent_empty, if (songs.isEmpty()) View.VISIBLE else View.GONE)
            PlayerWidget.last?.let { views.setInt(R.id.widget_bg, "setColorFilter", it.tint) }
            views.setOnClickPendingIntent(R.id.widget_root, PlayerWidget.openApp(app))
            manager.updateAppWidget(ids, views)
        }

        /** Plays the tapped song, then the rest of the recent songs. */
        private fun play(app: Context, videoId: String, pending: PendingResult) {
            scope.launch {
                try {
                    withTimeoutOrNull(GIVE_UP_MS) {
                        val songs = recentSongs(app, QUEUE_LENGTH)
                        val index = songs.indexOfFirst { it.videoId == videoId }
                        if (index < 0) return@withTimeoutOrNull
                        val controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync().await()
                        controller.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
                        controller.prepare()
                        controller.play()
                        // Stay connected until the service has taken over as a playing, foreground service.
                        delay(RELEASE_DELAY_MS)
                        controller.release()
                    }
                } catch (_: Exception) {
                    // Nothing to play it with right now; the widget stays as it is.
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
