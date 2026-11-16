package com.aurawave.player.ui

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aurawave.player.AppGraph
import com.aurawave.player.playback.PlayerConnection
import com.aurawave.player.playback.rangedHttp
import com.aurawave.player.playback.toTrack
import com.aurawave.innertube.Kind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The picture for the Song/Video switch. The sound keeps coming from the music player;
 * this plays only the video track of the same recording, muted, and keeps it in step.
 * Nothing of the video is fetched unless this is on screen.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPane(graph: AppGraph, player: PlayerConnection, videoId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val musicSpeed by graph.settings.speed.flow.collectAsStateWithLifecycle()
    val talkSpeed by graph.settings.podcastSpeed.flow.collectAsStateWithLifecycle()
    val speed = if (player.current?.toTrack()?.kind == Kind.EPISODE) talkSpeed else musicSpeed
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var problem by remember(videoId) { mutableStateOf<String?>(null) }
    val video = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(rangedHttp(graph.http))))
            .build()
            .apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                })
            }
    }
    DisposableEffect(Unit) { onDispose { video.release() } }

    LaunchedEffect(videoId) {
        val url = graph.videoUrl(videoId)
        if (url == null) {
            problem = "This recording has no video to show."
            return@LaunchedEffect
        }
        video.setMediaItem(MediaItem.fromUri(url), player.positionMs)
        video.prepare()
    }
    LaunchedEffect(speed) { video.playbackParameters = PlaybackParameters(speed) }
    // Follow the music while the app is on screen: play and pause with it, and catch up when
    // the two drift apart. Off screen the picture stops, so the music plays on without
    // fetching or decoding video nobody is watching.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) video.playWhenReady = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(videoId, player.playing, player.positionTick, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Just back on screen, the player's position is unknown until it reconnects.
                if (player.connected) {
                    video.playWhenReady = player.playing
                    val drift = video.currentPosition - player.positionMs
                    if (video.playbackState == Player.STATE_READY && abs(drift) > MAX_DRIFT_MS) {
                        video.seekTo(player.positionMs + if (player.playing) SEEK_LEAD_MS else 0)
                    }
                }
                delay(500)
            }
        }
    }

    Box(
        modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { video.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        )
        problem?.let {
            Text(it, color = Palette.Moon, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

private const val MAX_DRIFT_MS = 300L
private const val SEEK_LEAD_MS = 120L
