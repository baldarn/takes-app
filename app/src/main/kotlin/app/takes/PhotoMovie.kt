@file:OptIn(UnstableApi::class)

package app.takes

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.takes.core.PhotoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * A photo as a movie the timeline can hold, as the original's PhotoClip (PhotoClip.swift:7-65): the picture
 * upright, its longer side at most [PhotoClip.MAXIMUM_PIXELS], each side made even with a black row or column,
 * shown for [PhotoClip.SECONDS] seconds at [PhotoClip.FRAME_RATE] frames a second, in H.264 and silent.
 */
internal object PhotoMovie {
    /** Writes the photo at [uri] as a movie to [destination]; false, with nothing left behind, when it cannot. */
    suspend fun write(context: Context, uri: Uri, destination: File): Boolean {
        val still = File(destination.parentFile, ".${destination.name}.png")
        try {
            val picture = withContext(Dispatchers.IO) { decode(context.contentResolver, uri) } ?: return false
            withContext(Dispatchers.IO) {
                val (width, height) = PhotoClip.encodedSize(picture.width, picture.height)
                val even = if (width == picture.width && height == picture.height) picture
                else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(android.graphics.Color.BLACK); drawBitmap(picture, 0f, 0f, null) } }
                still.outputStream().use { even.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val item = MediaItem.Builder().setUri(Uri.fromFile(still)).setImageDurationMs(PhotoClip.SECONDS * 1000L).build()
            val edited = EditedMediaItem.Builder(item).setFrameRate(PhotoClip.FRAME_RATE).build()
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) = continuation.resume(Unit)

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                                continuation.resumeWithException(exportException)
                        }).build()
                    continuation.invokeOnCancellation { transformer.cancel() }
                    transformer.start(edited, destination.path)
                }
            }
            return destination.length() > 0
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            destination.delete()
            throw stopped
        } catch (_: Exception) {
            destination.delete()
            return false
        } finally {
            still.delete()
        }
    }

    /** The photo upright, its longer side brought down to the limit; a smaller one is left as it is. */
    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > PhotoClip.MAXIMUM_PIXELS) {
                    val scale = PhotoClip.MAXIMUM_PIXELS.toDouble() / longer
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PhotoClip.MAXIMUM_PIXELS) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            val turn = when (resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            decoded?.let { bitmap ->
                val scale = minOf(1f, PhotoClip.MAXIMUM_PIXELS.toFloat() / maxOf(bitmap.width, bitmap.height))
                if (turn == 0f && scale == 1f) bitmap
                else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postScale(scale, scale); postRotate(turn) }, true)
            }
        }
    } catch (_: Exception) {
        null
    }
}
