@file:OptIn(UnstableApi::class)

package app.takes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.LUTInput
import app.takes.core.LocalRecovery
import app.takes.core.LogColor
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The views each player of the preview draws its footage in, so that a grade with a local highlight recovery can read
 * the frame shown ([LocalRecoveryPreview]).
 */
object PreviewViews {
    private val views = WeakHashMap<Player, PlayerView>()

    fun attach(player: Player, view: PlayerView) {
        views[player] = view
    }

    /** The view [player] draws in and the texture its frames are in, when it draws in one. */
    fun of(player: Player): Pair<PlayerView, TextureView>? = views[player]?.let { view -> (view.videoSurfaceView as? TextureView)?.let { view to it } }
}

/**
 * The local highlight recovery of a log clip's grade in the preview (`YouRec/LogColor.swift:129-180`). The original
 * draws its preview through the same Core Image filter as its export, on a proxy of the footage, the neighbourhood
 * scaled with the frame; here the frame the player shows is read back small, at most [SHORT_EDGE] pixels on its short
 * side, the guided filter's coefficients are worked out from it by the core ([LocalRecovery.coefficients]) away from
 * the screen, and the shader draws each pixel with them, read between their points, recoded for the grade's table as
 * the export recodes it ([RecoveredColorLut]). While the footage plays the coefficients come from the latest frame
 * read, which can be a frame or two behind the one drawn.
 *
 * [ends] are the grade's ends, one for a grade that does not move; [luts] their tables as the preview's strips.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LocalRecoveryPreview(private val ends: List<ColorSettings>, private val profile: LUTInput, private val luts: List<Bitmap>) {
    /** Bumped each time new coefficients arrive, so that the picture is drawn again with them. */
    val version = mutableIntStateOf(0)

    @Volatile private var maps: List<Pair<Bitmap, Bitmap>?> = ends.map { null }
    private var mapSize = 1 to 1
    private var region = RectF(0f, 0f, 1f, 1f)
    private var busy = false
    private var lastStamp = Long.MIN_VALUE
    private var reading: Bitmap? = null

    private val shader by lazy {
        RuntimeShader(if (ends.size == 1) ONE else TWO).apply {
            setInputShader("lut", lookup(luts[0]))
            if (ends.size == 2) setInputShader("lutB", lookup(luts[1]))
            setFloatUniform("size", ColorGrade.DIMENSION.toFloat())
            ends.forEachIndexed { index, end ->
                val suffix = if (index == 0) "" else "B"
                val m = LogColor.matrix(profile)
                setFloatUniform("primaries$suffix", FloatArray(9) { m[(it % 3) * 3 + it / 3].toFloat() })
                val gains = ColorGrade.logGains(end)
                setFloatUniform("gains$suffix", gains[0].toFloat(), gains[1].toFloat(), gains[2].toFloat())
                setFloatUniform("highlights$suffix", (end.highlights ?: 0.0).coerceIn(-1.0, 1.0).toFloat())
            }
        }
    }

    private fun lookup(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
    }

    /**
     * Reads the frame [view] shows, when it has a new one, and works out its coefficients in the background; [onto]
     * is the view the grade's effect is drawn on, which the frame lies in.
     */
    fun sample(view: TextureView, onto: View?) {
        if (busy || !view.isAvailable || view.width <= 0 || view.height <= 0) return
        val stamp = view.surfaceTexture?.timestamp ?: return
        val scale = min(1f, SHORT_EDGE.toFloat() / min(view.width, view.height))
        val width = max(1, (view.width * scale).roundToInt())
        val height = max(1, (view.height * scale).roundToInt())
        if (stamp == lastStamp && mapSize == (width to height)) return
        val bitmap = reading?.takeIf { it.width == width && it.height == height } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reading = it }
        view.getBitmap(bitmap)
        lastStamp = stamp
        var left = 0f
        var top = 0f
        var at: View = view
        while (at !== onto) {
            left += at.left + at.translationX
            top += at.top + at.translationY
            at = at.parent as? View ?: break
        }
        val placed = RectF(left, top, left + view.width, top + view.height)
        read(bitmap, placed)
    }

    /** Works out the coefficients of [frame], code values, lying at [placed] in the effect's view. */
    fun read(frame: Bitmap, placed: RectF, wait: Boolean = false) {
        busy = true
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height).also { frame.getPixels(it, 0, width, 0, 0, width, height) }
        val work = Runnable {
            val codes = DoubleArray(pixels.size * 3) { i -> ((pixels[i / 3] shr (16 - 8 * (i % 3))) and 0xff) / 255.0 }
            val made = ends.map { end ->
                if (LocalRecovery.strength(end) == 0.0) return@map null
                val coefficients = LocalRecovery.coefficients(LocalRecovery.balancedLuminance(codes, end, profile), width, height)
                val (a, b) = LocalRecovery.packed(coefficients)
                Bitmap.createBitmap(a, width, height, Bitmap.Config.ARGB_8888) to Bitmap.createBitmap(b, width, height, Bitmap.Config.ARGB_8888)
            }
            val done = Runnable {
                maps = made
                mapSize = width to height
                region = placed
                busy = false
                version.intValue++
            }
            if (wait) done.run() else MAIN.post(done)
        }
        if (wait) work.run() else WORKER.execute(work)
    }

    /** The effect with the latest coefficients, at [fraction] of the way from the first end to the second. */
    fun effect(fraction: Double = 0.0): RenderEffect {
        val shader = shader
        val maps = maps
        ends.forEachIndexed { index, end ->
            val suffix = if (index == 0) "" else "B"
            val map = maps[index]
            shader.setFloatUniform("strength$suffix", if (map == null) 0f else LocalRecovery.strength(end).toFloat())
            val (a, b) = map ?: (BLANK to BLANK)
            shader.setInputShader("coefA$suffix", lookup(a))
            shader.setInputShader("coefB$suffix", lookup(b))
        }
        shader.setFloatUniform("region", region.left, region.top, region.width(), region.height())
        shader.setFloatUniform("mapSize", mapSize.first.toFloat(), mapSize.second.toFloat())
        if (ends.size == 2) shader.setFloatUniform("fraction", fraction.toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    companion object {
        /** The short side of the frame the coefficients are worked out on. */
        const val SHORT_EDGE = 270

        private val WORKER = Executors.newSingleThreadExecutor { Thread(it, "local-recovery").apply { isDaemon = true } }
        private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())
        private val BLANK: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

        // The Apple Log curve, Highlights and its inverse, the recovery of one pixel and the table, as RecoveredColorLut's.
        private const val COMMON = """
        uniform shader content;
        uniform float size;
        uniform float4 region;
        uniform float2 mapSize;
        const float LA = 47.28711236;
        const float LB = 0.05641088;
        const float LC = 0.69336945;
        const float LD = 0.08550479;
        const float LE = 0.00964052;
        const float KNEE = 0.35;
        const float3 LUMA = float3(0.2126, 0.7152, 0.0722);
        float logLight(float x) { return x >= LA * (0.01 + LB) * (0.01 + LB) ? exp2((x - LC) / LD) - LE : sqrt(max(0.0, x) / LA) - LB; }
        float logCode(float x) { float s = max(0.0, x + LB); return x >= 0.01 ? log2(max(x + LE, 0.00000001)) * LD + LC : LA * s * s; }
        float hlForward(float y, float a) {
            if (a == 0.0 || y <= KNEE) return y;
            float d = y - KNEE;
            if (a < 0.0) { float s = -a * 6.0; return KNEE + log(1.0 + s * d) / s; }
            return KNEE + d * (1.0 + 2.0 * a * d / (d + KNEE));
        }
        float hlInverse(float m, float a) {
            if (a == 0.0 || m <= KNEE) return m;
            float d = m - KNEE;
            if (a < 0.0) { float s = -a * 6.0; return KNEE + (exp(s * d) - 1.0) / s; }
            float q = 1.0 + 2.0 * a;
            return KNEE + (d - KNEE + sqrt((KNEE - d) * (KNEE - d) + 4.0 * q * d * KNEE)) / (2.0 * q);
        }
        float unpack(half4 p) { return (float(p.r) * 255.0 * 256.0 + float(p.g) * 255.0) / 65535.0; }
        float3 lightOf(float3 v) { return mix(v / 4.5, pow((v + 0.099) / 1.099, float3(1.0 / 0.45)), step(0.081, v)); }
        float3 signalOf(float3 l) { return mix(l * 4.5, 1.099 * pow(l, float3(0.45)) - 0.099, step(0.018, l)); }
        """

        /** An end's uniforms and children, named with [suffix], with `recoded$suffix` and `cube$suffix` over them. */
        private fun end(suffix: String) = """
        uniform shader lut$suffix;
        uniform shader coefA$suffix;
        uniform shader coefB$suffix;
        uniform float strength$suffix;
        uniform float3x3 primaries$suffix;
        uniform float3 gains$suffix;
        uniform float highlights$suffix;
        float3 recoded$suffix(float3 c, float2 xy) {
            if (strength$suffix <= 0.0) return c;
            float3 light = float3(logLight(c.r), logLight(c.g), logLight(c.b));
            float y0 = max(0.0, dot(max(primaries$suffix * light, 0.0) * gains$suffix, LUMA));
            if (y0 <= 0.000001) return c;
            float hl = highlights$suffix;
            float yb = hlForward(y0, hl);
            float2 at = clamp((xy - region.xy) / region.zw, 0.0, 1.0) * mapSize;
            float a = unpack(coefA$suffix.eval(at));
            float b = unpack(coefB$suffix.eval(at)) * ${LocalRecovery.B_RANGE} + ${LocalRecovery.B_LOW};
            float y = max(0.000001, yb);
            float l = log2(y);
            float baseLog = a * l + b;
            float base = exp2(baseLog);
            float adjusted = base <= 0.35 ? base : 0.35 + log(1.0 + 12.0 * (base - 0.35)) / 12.0;
            float excess = max(0.0, adjusted - 0.6);
            float mappedBase = adjusted <= 0.6 ? adjusted : 0.6 + 0.4 * excess / (excess + 0.4);
            float local = mappedBase * exp2(l - baseLog);
            float spike = max(0.0, local - 0.9);
            local = local <= 0.9 ? local : 0.9 + 0.1 * spike / (spike + 0.1);
            float originalExcess = max(0.0, y - 0.6);
            float original = y <= 0.6 ? y : 0.6 + 0.4 * originalExcess / (originalExcess + 0.4);
            float target = mix(original, local, strength$suffix);
            float sceneY = target <= 0.6 ? target : 0.6 + 0.4 * (target - 0.6) / max(0.000001, 1.0 - target);
            float scale = hlInverse(sceneY / y * yb, hl) / y0;
            return clamp(float3(logCode(light.r * scale), logCode(light.g * scale), logCode(light.b * scale)), 0.0, 1.0);
        }
        float3 cube$suffix(float3 v) {
            float3 position = clamp(v, 0.0, 1.0) * (size - 1.0);
            float slice = floor(position.b);
            float next = min(slice + 1.0, size - 1.0);
            float3 low = float3(lut$suffix.eval(float2(position.r + slice * size + 0.5, position.g + 0.5)).rgb);
            float3 high = float3(lut$suffix.eval(float2(position.r + next * size + 0.5, position.g + 0.5)).rgb);
            return mix(low, high, position.b - slice);
        }
        """

        private val ONE = """
        $COMMON
        ${end("")}
        half4 main(float2 xy) {
            half4 color = content.eval(xy);
            if (color.a <= 0.0) return color;
            float3 c = clamp(float3(color.rgb / color.a), 0.0, 1.0);
            return half4(half3(cube(recoded(c, xy))) * color.a, color.a);
        }
        """

        // Two ends mixed as SDR video's light, as ColorLuts' moving grade mixes them.
        private val TWO = """
        $COMMON
        ${end("")}
        ${end("B")}
        uniform float fraction;
        half4 main(float2 xy) {
            half4 color = content.eval(xy);
            if (color.a <= 0.0) return color;
            float3 c = clamp(float3(color.rgb / color.a), 0.0, 1.0);
            float3 a = cube(recoded(c, xy));
            float3 b = cubeB(recodedB(c, xy));
            float3 mixed = fraction <= 0.0 ? a : fraction >= 1.0 ? b : signalOf(clamp(mix(lightOf(clamp(a, 0.0, 1.0)), lightOf(clamp(b, 0.0, 1.0)), fraction), 0.0, 1.0));
            return half4(half3(mixed) * color.a, color.a);
        }
        """
    }
}
