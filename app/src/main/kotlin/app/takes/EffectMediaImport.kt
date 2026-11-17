package app.takes

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.takes.core.ProjectDisk
import app.takes.core.VideoEffectFormulasC
import java.io.File
import java.util.UUID
import app.takes.core.Size as GeometrySize

/** What a video effect's picture, movie or clean plate picked from the phone became. */
internal sealed interface EffectMediaPicked {
    /** Kept in the folder under [name]. */
    data class Kept(val name: String) : EffectMediaPicked

    /** Not a picture or a movie that can be read. */
    data object Unreadable : EffectMediaPicked

    /** A clean plate whose shape is not the recording's. */
    data object Mismatch : EffectMediaPicked
}

/** Which of an effect's media is picked: a background's picture or movie, or a Chroma Key's clean plate. */
internal enum class EffectMediaKind { Picture, Movie, Plate }

/**
 * Copies what is picked for a video effect into the folder where its effects read it: a picture under a name made
 * from its contents, as before; a movie under a new name, as the original copies it (VideoEffectsEditor.swift:814-818);
 * a clean plate only when its shape, upright, is the recording's within 1 %, as the original refuses another
 * (VideoEffectCleanPlate.swift:42-49, VideoEffectsEditor.swift:822-827).
 */
internal object EffectMediaImport {
    fun import(resolver: ContentResolver, uri: Uri, folder: File, kind: EffectMediaKind, matching: GeometrySize? = null): EffectMediaPicked = try {
        if (kind == EffectMediaKind.Movie) movie(resolver, uri, folder) else picture(resolver, uri, folder, if (kind == EffectMediaKind.Plate) matching else null)
    } catch (_: Exception) {
        EffectMediaPicked.Unreadable
    }

    private fun picture(resolver: ContentResolver, uri: Uri, folder: File, matching: GeometrySize?): EffectMediaPicked {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return EffectMediaPicked.Unreadable
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return EffectMediaPicked.Unreadable
        if (matching != null) {
            val turned = when (runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
            val size = if (turned) GeometrySize(bounds.outHeight.toDouble(), bounds.outWidth.toDouble()) else GeometrySize(bounds.outWidth.toDouble(), bounds.outHeight.toDouble())
            if (!VideoEffectFormulasC.cleanPlateCompatible(size, matching)) return EffectMediaPicked.Mismatch
        }
        val extension = when (bounds.outMimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heif", "image/heic" -> "heic"
            else -> "jpg"
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        folder.mkdirs()
        val file = File(folder, "effect-picture-${hash.take(32)}.$extension")
        if (!file.isFile) ProjectDisk.writeAtomically(bytes, file)
        return EffectMediaPicked.Kept(file.name)
    }

    private fun movie(resolver: ContentResolver, uri: Uri, folder: File): EffectMediaPicked {
        val extension = when (resolver.getType(uri)) {
            "video/quicktime" -> "mov"
            "video/x-m4v" -> "m4v"
            else -> "mp4"
        }
        folder.mkdirs()
        val file = File(folder, "effect-${UUID.randomUUID()}.$extension")
        val partial = File(folder, ".${file.name}.partial")
        try {
            (resolver.openInputStream(uri) ?: return EffectMediaPicked.Unreadable).use { input -> partial.outputStream().use { input.copyTo(it) } }
            // A movie with a picture and a length.
            val retriever = MediaMetadataRetriever()
            val usable = try {
                retriever.setDataSource(partial.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0
            } finally {
                runCatching { retriever.release() }
            }
            if (!usable || !partial.renameTo(file)) return EffectMediaPicked.Unreadable
            return EffectMediaPicked.Kept(file.name)
        } finally {
            partial.delete()
        }
    }
}
