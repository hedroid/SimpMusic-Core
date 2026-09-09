package com.maxrave.media3.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.util.concurrent.ExecutionException

@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : BitmapLoader {
    // media3's DefaultMediaNotificationProvider posts the notification FIRST and attaches the
    // artwork only when the returned future completes — and every newer refresh discards that
    // pending attach. With lyric-line metadata swaps re-posting the notification every few
    // seconds, a purely async future kept losing the race: the notification (and the QS media
    // capsule rendered from its artwork) stayed artless most of the time. A synchronous hit for
    // recently served URIs makes the first post carry the art. The cache holds the same Bitmap
    // instances Coil's memory cache already owns, so it adds no pixel memory of its own.
    private val recentBitmaps =
        object : LinkedHashMap<Uri, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Bitmap>): Boolean = size > 4
        }

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        coroutineScope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("Could not decode image data")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        synchronized(recentBitmaps) { recentBitmaps[uri] }?.let { return Futures.immediateFuture(it) }
        return coroutineScope.future(Dispatchers.IO) {
            val result =
                (
                    context.imageLoader.execute(
                        ImageRequest
                            .Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .build(),
                    )
                )
            if (result is ErrorResult) {
                throw ExecutionException(result.throwable)
            }
            try {
                val bitmap = result.image?.toBitmap() ?: throw ExecutionException(NullPointerException())
                synchronized(recentBitmaps) { recentBitmaps[uri] = bitmap }
                bitmap
            } catch (e: Exception) {
                throw ExecutionException(e)
            }
        }
    }
}