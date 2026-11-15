package app.takes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.CubeLUT
import java.io.File
import kotlin.math.roundToInt

/**
 * A clip's color as the lookup table both the preview and the export apply.
 * Tables are computed once per distinct settings and kept for reuse.
 */
object ColorLuts {
    private const val SIZE = ColorGrade.DIMENSION
    private val tables = LruCache<String, FloatArray>(16)
    private val strips = LruCache<String, Bitmap>(8)

    /** [folder] is the project folder, where an imported LUT's file lives. A LUT file that cannot be read leaves only the balance. */
    fun table(settings: ColorSettings, folder: File): FloatArray {
        val key = folder.path + settings
        tables.get(key)?.let { return it }
        val imported = settings.imported?.let { runCatching { CubeLUT.parse(File(folder, it.filename).readBytes()) }.getOrNull() }
        val effective = if (settings.imported != null && imported == null) settings.copy(amount = 0.0) else settings
        return ColorGrade.table(effective, imported).also { tables.put(key, it) }
    }

    private fun pixel(table: FloatArray, red: Int, green: Int, blue: Int): Int {
        val index = ((blue * SIZE + green) * SIZE + red) * 3
        fun channel(offset: Int) = (table[index + offset] * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /** The table in the layout Media3's color LUT effect takes: `[red][green][blue]`, opaque ARGB. */
    fun cube(settings: ColorSettings, folder: File): Array<Array<IntArray>> {
        val table = table(settings, folder)
        return Array(SIZE) { red -> Array(SIZE) { green -> IntArray(SIZE) { blue -> pixel(table, red, green, blue) } } }
    }

    // The table as a picture for the preview shader: the blue slices side by side,
    // red across each slice and green down.
    private fun strip(settings: ColorSettings, folder: File): Bitmap {
        val key = folder.path + settings
        strips.get(key)?.let { return it }
        val table = table(settings, folder)
        val pixels = IntArray(SIZE * SIZE * SIZE)
        for (green in 0 until SIZE) for (blue in 0 until SIZE) for (red in 0 until SIZE) {
            pixels[green * SIZE * SIZE + blue * SIZE + red] = pixel(table, red, green, blue)
        }
        return Bitmap.createBitmap(pixels, SIZE * SIZE, SIZE, Bitmap.Config.ARGB_8888).also { strips.put(key, it) }
    }

    // Looks the picture's color up in the strip. Red and green are interpolated by the
    // strip's own filtering inside a slice; blue is interpolated here between two slices.
    private const val SHADER = """
        uniform shader content;
        uniform shader lut;
        uniform float size;
        half4 main(float2 xy) {
            half4 color = content.eval(xy);
            if (color.a <= 0.0) return color;
            float3 position = clamp(color.rgb / color.a, 0.0, 1.0) * (size - 1.0);
            float slice = floor(position.b);
            float next = min(slice + 1.0, size - 1.0);
            half3 low = lut.eval(float2(position.r + slice * size + 0.5, position.g + 0.5)).rgb;
            half3 high = lut.eval(float2(position.r + next * size + 0.5, position.g + 0.5)).rgb;
            return half4(mix(low, high, half(position.b - slice)) * color.a, color.a);
        }
    """

    /** The grade as an effect for a view showing the video. Older Android versions preview without color; the export is unaffected. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun previewEffect(settings: ColorSettings, folder: File): RenderEffect {
        val lookup = BitmapShader(strip(settings, folder), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        val shader = RuntimeShader(SHADER).apply {
            setInputShader("lut", lookup)
            setFloatUniform("size", SIZE.toFloat())
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
