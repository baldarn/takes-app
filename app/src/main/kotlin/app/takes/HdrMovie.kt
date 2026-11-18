@file:OptIn(UnstableApi::class)

package app.takes

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import java.io.File
import kotlin.math.roundToInt

/** HDR movies: which recordings are HDR, and what an HDR movie is written as. */
object HdrMovie {
    /** An HDR movie's picture: HLG, BT.2020, limited range, as the original writes it (YouRec/TrackRenderer.swift:166-168). */
    val COLOR: ColorInfo = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).setColorRange(C.COLOR_RANGE_LIMITED).build()

    /**
     * The HLG signal and linear light counted from SDR white, in GLSL: `hlgLin` takes the signal (0…1) to light,
     * where SDR white (75 % of the signal, ITU-R BT.2408) is 1 and the brightest light about 3.77; `hlgEnc` takes it
     * back, holding the light to what HLG holds. `BT709_TO_BT2020` takes linear BT.709 primaries into BT.2020 ones.
     * SDR goes into HLG as light of the scene: SDR video's signal taken back to light by its own curve, its white at
     * HLG's reference white (ITU-R BT.2408's scene-light mapping).
     */
    const val GLSL = """
        const float HLG_WHITE = 0.26496256;
        float hlgLight(float e) { return e <= 0.5 ? e * e / 3.0 : (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0; }
        float hlgSignal(float l) { return l <= 1.0 / 12.0 ? sqrt(3.0 * l) : 0.17883277 * log(12.0 * l - 0.28466892) + 0.55991073; }
        vec3 hlgLin(vec3 c) { c = clamp(c, 0.0, 1.0); return vec3(hlgLight(c.r), hlgLight(c.g), hlgLight(c.b)) / HLG_WHITE; }
        vec3 hlgEnc(vec3 c) { c = clamp(c * HLG_WHITE, 0.0, 1.0); return vec3(hlgSignal(c.r), hlgSignal(c.g), hlgSignal(c.b)); }
        // SDR video's own curve (BT.709), from its signal to its light and back; the light may go past SDR white, and
        // below black outside BT.709's colors.
        vec3 sdrLin(vec3 v) { v = clamp(v, 0.0, 1.0); return mix(v / 4.5, pow((v + 0.099) / 1.099, vec3(1.0 / 0.45)), step(0.081, v)); }
        vec3 sdrEnc(vec3 l) { vec3 a = abs(l); return sign(l) * mix(a * 4.5, 1.099 * pow(a, vec3(0.45)) - 0.099, step(0.018, a)); }
        const mat3 BT709_TO_BT2020 = mat3(0.6274039, 0.0690973, 0.0163914, 0.3292830, 0.9195404, 0.0880133, 0.0433131, 0.0113623, 0.8955953);
        const mat3 BT2020_TO_BT709 = mat3(1.6604910, -0.1245505, -0.0181508, -0.5876411, 1.1328999, -0.1005789, -0.0728499, -0.0083494, 1.1187297);
        // SDR footage or an SDR color in an HLG picture, SDR white at HLG's; an HLG picture as SDR, held to SDR white.
        vec3 sdrToHlg(vec3 v) { return hlgEnc(BT709_TO_BT2020 * sdrLin(v)); }
        vec3 hlgToSdr(vec3 c) { return sdrEnc(clamp(BT2020_TO_BT709 * hlgLin(c), 0.0, 1.0)); }
        // An HLG picture as SDR for a creative effect, its highlights tone-mapped rather than clipped (core's
        // HdrToneMap.hlgToSdr, YouRec/VideoEffects/MetalVideoEffects.swift:206-221): display light from reference white
        // by BT.2100's OOTF at 1000 nits, BT.2390's EETF from 1000/203 times reference white down to it, on the
        // brightest channel with the same gain for all three, then BT.709's primaries and curve.
        float pqOf(float nits) {
            float y = pow(max(nits, 0.0) / 10000.0, 0.1593017578125);
            return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
        }
        float pqNits(float e) {
            float p = pow(clamp(e, 0.0, 1.0), 1.0 / 78.84375);
            return 10000.0 * pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
        }
        // PQ of the 1000-nit peak and of reference white's share of it (HdrToneMapTest checks them).
        float eetf(float light) {
            const float SOURCE_PEAK = 0.75182710;
            const float MAX_LUM = 0.77237025;
            const float KNEE = 1.5 * MAX_LUM - 0.5;
            float e1 = pqOf(light * 203.0) / SOURCE_PEAK;
            if (e1 < KNEE) return light;
            if (e1 >= 1.0) return 1.0;
            float t = (e1 - KNEE) / (1.0 - KNEE);
            float t2 = t * t;
            float t3 = t2 * t;
            float e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * KNEE + (t3 - 2.0 * t2 + t) * (1.0 - KNEE) + (-2.0 * t3 + 3.0 * t2) * MAX_LUM;
            return pqNits(e2 * SOURCE_PEAK) / 203.0;
        }
        vec3 hlgToDisplaySdr(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            vec3 scene = vec3(hlgLight(c.r), hlgLight(c.g), hlgLight(c.b));
            float ys = dot(scene, vec3(0.2627, 0.6780, 0.0593));
            vec3 light = ys > 0.0 ? scene * pow(ys, 0.2) * (1000.0 / 203.0) : vec3(0.0);
            float brightest = max(light.r, max(light.g, light.b));
            if (brightest > 0.0) light *= eetf(brightest) / brightest;
            return sdrEnc(clamp(BT2020_TO_BT709 * light, 0.0, 1.0));
        }
    """

    /**
     * Whether this device writes HDR movies: Android 13 or later, with an H.265 encoder that edits HDR in HLG
     * (Media3's test of the encoder, [EncoderUtil.getSupportedEncodersForHdrEditing]). Elsewhere an HDR movie is
     * tone-mapped to SDR, as every movie was before.
     */
    fun supported(): Boolean = supported && !refused

    // Set when an HDR export failed on this device after all, or by a test to take the SDR way.
    @Volatile private var refused = false

    /** No HDR movie is made any more while the app runs: an HDR export failed ([Exporter]), or a test asks for the SDR way. */
    fun refuse(refuse: Boolean = true) {
        refused = refuse
    }

    private val supported by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { EncoderUtil.getSupportedEncodersForHdrEditing(MimeTypes.VIDEO_H265, COLOR).isNotEmpty() }.getOrDefault(false)
    }

    // sdrToHlg in numbers: SDR's signal to light, a step at a time, and HLG's signal for light from 0 to its brightest.
    private val sdrLight = FloatArray(256) { step ->
        val v = step / 255.0
        (if (v < 0.081) v / 4.5 else Math.pow((v + 0.099) / 1.099, 1 / 0.45)).toFloat()
    }
    private const val STEPS = 4096
    private val hlgSignal = IntArray(STEPS + 1) { step ->
        val light = step.toDouble() / STEPS
        val signal = if (light <= 1.0 / 12) Math.sqrt(3 * light) else 0.17883277 * Math.log(12 * light - 0.28466892) + 0.55991073
        (signal * 255).roundToInt().coerceIn(0, 255)
    }

    /** A color given as SDR, ARGB with its opacity kept apart, as the HLG signal of the same light ([GLSL]'s `sdrToHlg`). */
    fun sdrToHlg(argb: Int): Int {
        val r = sdrLight[(argb shr 16) and 0xff]
        val g = sdrLight[(argb shr 8) and 0xff]
        val b = sdrLight[argb and 0xff]
        fun signal(light: Float) = hlgSignal[(light * HLG_WHITE * STEPS).roundToInt().coerceIn(0, STEPS)]
        val red = signal(0.6274039f * r + 0.3292830f * g + 0.0433131f * b)
        val green = signal(0.0690973f * r + 0.9195404f * g + 0.0113623f * b)
        val blue = signal(0.0163914f * r + 0.0880133f * g + 0.8955953f * b)
        return (argb and 0xff000000.toInt()) or (red shl 16) or (green shl 8) or blue
    }

    /** The HLG signal's light of SDR white, as a share of its brightest (75 % of the signal, ITU-R BT.2408). */
    private const val HLG_WHITE = 0.26496256f

    /** The HDR recordings among [files], with their transfer, when the device writes HDR movies; else none. */
    fun transfers(files: Map<String, File>): Map<String, Int> =
        if (!supported()) emptyMap() else files.mapNotNull { (id, file) -> transfer(file)?.let { id to it } }.toMap()

    /** The transfer of [file]'s picture when it is HDR, HLG or PQ ([C.COLOR_TRANSFER_HLG], [C.COLOR_TRANSFER_ST2084]); else null. */
    fun transfer(file: File): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_COLOR_TRANSFER) }?.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                ?.let {
                    when (it) {
                        MediaFormat.COLOR_TRANSFER_HLG -> C.COLOR_TRANSFER_HLG
                        MediaFormat.COLOR_TRANSFER_ST2084 -> C.COLOR_TRANSFER_ST2084
                        else -> null
                    }
                }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
