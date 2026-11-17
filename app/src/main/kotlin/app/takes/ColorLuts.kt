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
import app.takes.core.GradeMotion
import app.takes.core.LUTInput
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

    /**
     * [folder] is the project folder, where an imported LUT's file lives. A LUT file that cannot be read leaves only the balance.
     * [sourceLog] is the log profile of the recording, whose code values the table then takes ([LogFootage]).
     */
    fun table(settings: ColorSettings, folder: File, sourceLog: LUTInput? = null): FloatArray {
        val key = folder.path + settings.copy(id = "") + sourceLog // the same grade on another clip is the same table
        tables.get(key)?.let { return it }
        val imported = settings.imported?.let { runCatching { CubeLUT.parse(File(folder, it.filename).readBytes()) }.getOrNull() }
        val effective = if (settings.imported != null && imported == null) settings.copy(amount = 0.0) else settings
        return ColorGrade.table(effective, imported, sourceLog).also { tables.put(key, it) }
    }

    private fun pixel(table: FloatArray, red: Int, green: Int, blue: Int): Int {
        val index = ((blue * SIZE + green) * SIZE + red) * 3
        fun channel(offset: Int) = (table[index + offset] * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /** The table in the layout Media3's color LUT effect takes: `[red][green][blue]`, opaque ARGB. */
    fun cube(settings: ColorSettings, folder: File, sourceLog: LUTInput? = null): Array<Array<IntArray>> {
        val table = table(settings, folder, sourceLog)
        return Array(SIZE) { red -> Array(SIZE) { green -> IntArray(SIZE) { blue -> pixel(table, red, green, blue) } } }
    }

    // The table as a picture for the preview shader: the blue slices side by side,
    // red across each slice and green down.
    private fun strip(settings: ColorSettings, folder: File, sourceLog: LUTInput?): Bitmap {
        val key = folder.path + settings.copy(id = "") + sourceLog // the same grade on another clip is the same table
        strips.get(key)?.let { return it }
        val table = table(settings, folder, sourceLog)
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

    // The two ends of a moving grade looked up as above, and dissolved as light ([GradeMotion.dissolve]).
    private const val MOVING = """
        uniform shader content;
        uniform shader lutA;
        uniform shader lutB;
        uniform float size;
        uniform float fraction;
        float3 light(float3 v) { return mix(v / 4.5, pow((v + 0.099) / 1.099, float3(1.0 / 0.45)), step(0.081, v)); }
        float3 signal(float3 l) { return mix(l * 4.5, 1.099 * pow(l, float3(0.45)) - 0.099, step(0.018, l)); }
        half4 main(float2 xy) {
            half4 color = content.eval(xy);
            if (color.a <= 0.0) return color;
            float3 position = clamp(color.rgb / color.a, 0.0, 1.0) * (size - 1.0);
            float slice = floor(position.b);
            float next = min(slice + 1.0, size - 1.0);
            float2 low = float2(position.r + slice * size + 0.5, position.g + 0.5);
            float2 high = float2(position.r + next * size + 0.5, position.g + 0.5);
            float3 a = mix(float3(lutA.eval(low).rgb), float3(lutA.eval(high).rgb), position.b - slice);
            float3 b = mix(float3(lutB.eval(low).rgb), float3(lutB.eval(high).rgb), position.b - slice);
            float3 mixed = signal(clamp(mix(light(clamp(a, 0.0, 1.0)), light(clamp(b, 0.0, 1.0)), fraction), 0.0, 1.0));
            return half4(half3(mixed) * color.a, color.a);
        }
    """

    /**
     * A deck's grade in the preview. A fixed grade is one effect; a moving one ([GradeMotion]) is drawn at the
     * fraction of its motion at the moment shown: A's table alone before it starts, B's alone once there, and between
     * the two the picture graded by both and dissolved, as the export draws it ([MovingColorLut]).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class Preview(settings: ColorSettings, private val folder: File, private val sourceLog: LUTInput?) {
        private val ends = GradeMotion.ends(settings)
        private val fixed by lazy { previewEffect(ends?.first ?: settings, folder, sourceLog) }
        private val atB by lazy { previewEffect(ends!!.second, folder, sourceLog) }
        private val between by lazy {
            fun lookup(grade: ColorSettings) = BitmapShader(strip(grade, folder, sourceLog), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
            RuntimeShader(MOVING).apply {
                setInputShader("lutA", lookup(ends!!.first))
                setInputShader("lutB", lookup(ends.second))
                setFloatUniform("size", SIZE.toFloat())
            }
        }

        /** Whether the grade moves, so that what it draws depends on the moment shown. */
        val moves: Boolean get() = ends != null

        /** The effect at [fraction] of the way from A to B; a fixed grade ignores it. */
        fun effect(fraction: Double = 0.0): RenderEffect = when {
            ends == null || fraction <= 0 -> fixed
            fraction >= 1 -> atB
            else -> {
                between.setFloatUniform("fraction", fraction.toFloat())
                RenderEffect.createRuntimeShaderEffect(between, "content")
            }
        }
    }

    /** The grade as an effect for a view showing the video. Older Android versions preview without color; the export is unaffected. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun previewEffect(settings: ColorSettings, folder: File, sourceLog: LUTInput? = null): RenderEffect {
        val lookup = BitmapShader(strip(settings, folder, sourceLog), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        val shader = RuntimeShader(SHADER).apply {
            setInputShader("lut", lookup)
            setFloatUniform("size", SIZE.toFloat())
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
