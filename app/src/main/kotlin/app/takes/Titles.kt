@file:OptIn(UnstableApi::class)

package app.takes

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.takes.core.TextOverlay
import app.takes.core.TitleMotionRanges

/** Draws a title the same way in the editor preview and in the exported movie. */
object TitleRenderer {
    fun draw(canvas: Canvas, width: Float, height: Float, title: TextOverlay, opacity: Float = 1f) {
        if (title.text.isBlank() || width < 1 || height < 1) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (title.size * minOf(width, height)).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(title.text, 0, title.text.length, paint, (width * 0.9f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        // Centered on its anchor, kept inside the frame.
        val top = (title.y * height - layout.height / 2f).toFloat().coerceIn(0f, maxOf(0f, height - layout.height))
        canvas.translate((title.x * width - layout.width / 2f).toFloat().coerceIn(0f, maxOf(0f, width - layout.width)), top)
        // A dark outline keeps white text readable over any picture.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = paint.textSize * 0.12f
        paint.strokeJoin = Paint.Join.ROUND
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.color = Color.BLACK
        paint.alpha = alpha
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        layout.draw(canvas)
        canvas.restore()
    }
}


/**
 * Titles of an export's frame given to a shader as textures, one a slot: a frame-sized bitmap drawn by
 * [TitlePainter], as the preview draws them, with its first row at the top and its colors multiplied by their
 * opacity, as Android keeps them. [TransitionEffect] has a slot a deck, [LayersEffect] one a lane: the shader lays a
 * slot's titles over the picture of its deck or lane before the transition combines the two sides, so that a title is
 * wiped, keyed, slid and blurred with its clip, as the original draws it (YouRec/VideoToolCompositor.swift:282,
 * :409-411). A slot is drawn again only when what it shows changes.
 *
 * It is made and used on the export's graphics context.
 */
internal class TitleTextures(private val slots: Int, private val width: Int, private val height: Int, assets: AssetManager, folder: java.io.File? = null) {
    // The pictures laid over clips are drawn under their titles, from the project's [folder].
    private val painter = TitlePainter(assets, folder?.let { OverlayPictures(it) })
    private val bitmaps = arrayOfNulls<Bitmap>(slots)
    private val shown = Array(slots) { emptyList<TitleMotionRanges.Shown>() }

    /** The texture of each slot's titles, 0 before it is first drawn. */
    val textures = IntArray(slots)

    /** Whether each slot has titles in this frame. */
    val drawn = BooleanArray(slots)

    /** Draws [wanted], the titles of each slot in this frame (none for a slot past its end). */
    fun prepare(wanted: List<List<TitleMotionRanges.Shown>>) {
        for (slot in 0 until slots) {
            // Opacity in steps, so a steady title is drawn once. A title on its way in or out is drawn for every frame.
            val titles = wanted.getOrNull(slot).orEmpty().map { it.copy(share = (it.share * 64).toInt() / 64.0) }.filter { it.share > 0 }
            drawn[slot] = titles.isNotEmpty()
            if (titles.isEmpty() || titles == shown[slot]) continue
            shown[slot] = titles
            val bitmap = bitmaps[slot] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps[slot] = it }
            bitmap.eraseColor(Color.TRANSPARENT)
            painter.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), titles)
            if (textures[slot] == 0) {
                val made = IntArray(1)
                GLES20.glGenTextures(1, made, 0)
                textures[slot] = made[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, made[0])
                for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
                for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
            GlUtil.checkGlError()
        }
    }

    fun release() {
        textures.filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        textures.fill(0)
        bitmaps.forEach { it?.recycle() }
    }
}
