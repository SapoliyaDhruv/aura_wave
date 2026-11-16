package com.aurawave.player.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.KeyEvent
import android.widget.RemoteViews
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aurawave.innertube.InnerTube
import com.aurawave.player.MainActivity
import com.aurawave.player.R
import androidx.compose.ui.graphics.toArgb
import com.aurawave.player.ui.Aura
import com.aurawave.player.ui.auraOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home-screen widget. The playback service redraws it whenever the song or the
 * play state changes. Play/Pause sends the media-button press headphones do; Next and
 * Previous connect to the playback service (see [skip]). All three work when the app has
 * been closed: the service reopens the saved queue.
 */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, last)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEXT, ACTION_PREVIOUS -> skip(context.applicationContext, next = intent.action == ACTION_NEXT, goAsync())
            else -> super.onReceive(context, intent)
        }
    }

    /**
     * Next and Previous. Media3's media-button receiver passes on only Play/Pause, so
     * these connect to the playback service themselves, as the app's own screens do. With
     * the app closed, that starts the service, which reopens the saved queue first.
     */
    private fun skip(app: Context, next: Boolean, pending: PendingResult) {
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val finish = Runnable {
            if (!finished) {
                finished = true
                MediaController.releaseFuture(future)
                pending.finish()
            }
        }
        future.addListener({
            runCatching {
                val controller = future.get()
                if (next) controller.seekToNextMediaItem() else controller.seekToPrevious()
            }
            // Stay connected briefly, so the service has redrawn the widget and saved the new place before it may close.
            main.removeCallbacks(finish)
            main.postDelayed(finish, RELEASE_DELAY_MS)
        }, ContextCompat.getMainExecutor(app))
        // A broadcast may run for about ten seconds; give up well before that if the service never answers.
        main.postDelayed(finish, CONNECT_TIMEOUT_MS)
    }

    /** The widget was resized: one row shows the slim strip, two or more the card. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, last)
    }

    /** What the widget shows. [tint] and [accent] are the song's colours, taken from its cover. */
    data class State(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val cover: Bitmap?,
        val coverUrl: String,
        val tint: Int,
        val accent: Int,
    )

    companion object {
        @Volatile
        private var last: State? = null

        /** Redraws every AuraWave widget. Loads the cover, and reads its colours, when the song changed. */
        suspend fun update(context: Context, title: String, artist: String, playing: Boolean, coverUrl: String) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return // no widget on the home screen: nothing to do
            val previous = last
            val state = if (previous != null && previous.coverUrl == coverUrl) {
                previous.copy(title = title, artist = artist, playing = playing)
            } else {
                val cover = loadCover(context, coverUrl)
                val aura = cover?.let { withContext(Dispatchers.Default) { auraOf(it) } } ?: Aura.Default
                val rounded = cover?.let { withContext(Dispatchers.Default) { rounded(it) } }
                State(title, artist, playing, rounded, coverUrl, aura.deep.toArgb(), aura.accent.toArgb())
            }
            last = state
            render(context, state)
        }

        private suspend fun loadCover(context: Context, url: String): Bitmap? {
            if (url.isEmpty()) return null
            val request = ImageRequest.Builder(context).data(InnerTube.bigCover(url, COVER_PX)).size(COVER_PX).allowHardware(false).build()
            return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }

        /** The cover as a square with rounded corners; widgets cannot round an image themselves on older Android. */
        private fun rounded(source: Bitmap): Bitmap {
            val side = minOf(source.width, source.height)
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { setTranslate(-(source.width - side) / 2f, -(source.height - side) / 2f) })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            val radius = side * COVER_CORNER
            Canvas(out).drawRoundRect(0f, 0f, side.toFloat(), side.toFloat(), radius, radius, paint)
            return out
        }

        private fun render(context: Context, state: State?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            for (id in ids) {
                // The launcher reports the widget's height; until it has, assume the size the widget starts at.
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val large = height == 0 || height >= LARGE_FROM_DP
                manager.updateAppWidget(id, views(context, state, large))
            }
        }

        private fun views(context: Context, state: State?, large: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, if (large) R.layout.widget_player_large else R.layout.widget_player)
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
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            views.setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, skipButton(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, skipButton(context, ACTION_PREVIOUS))
            return views
        }

        /** Enough for the two-row card's cover on a dense screen. */
        private const val COVER_PX = 320
        private const val COVER_CORNER = 0.14f
        /** Widgets at least this tall (in dp) get the card; one launcher row is well under it. */
        private const val LARGE_FROM_DP = 100
        private const val ACTION_NEXT = "com.aurawave.player.widget.NEXT"
        private const val ACTION_PREVIOUS = "com.aurawave.player.widget.PREVIOUS"
        private const val RELEASE_DELAY_MS = 1_500L
        private const val CONNECT_TIMEOUT_MS = 8_000L

        private fun skipButton(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
