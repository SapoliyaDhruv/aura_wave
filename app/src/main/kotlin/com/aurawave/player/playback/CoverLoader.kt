package com.aurawave.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aurawave.innertube.InnerTube
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Covers for the notification, the lock screen and Bluetooth displays. They come through
 * the app's own picture loader, in the size the player screen shows, so a cover that has
 * been on screen is not downloaded a second time and still appears with no connection
 * (a downloaded song played offline).
 */
@UnstableApi
class CoverLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Not a picture")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val request = ImageRequest.Builder(context)
            .data(InnerTube.bigCover(uri.toString(), COVER_PX))
            .allowHardware(false) // the system needs to read the pixels
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: throw IOException("The cover could not be loaded: $uri")
    }

    private companion object {
        /** The size the player screen asks for, so the two share one copy. */
        const val COVER_PX = 720
    }
}
