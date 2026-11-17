package app.takes.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.withSign

/*
 * The model of the original's video effects: what an effect on a clip is, how
 * its values move in time, and how it is saved. Ported from
 * YouRec/VideoEffects/VideoEffectModel.swift (cited below as `Model`), with the
 * same names, constants and rules. The saved shape is the one Swift's
 * synthesized coding gives, so that a recipe written by either app reads in
 * the other.
 *
 * Source-time values survive trims, pauses, speed changes and clip splits.
 * Categorical parameters use hold interpolation; continuous values interpolate.
 */

/** Where an effect sits in the pipeline, and under which heading the applied list shows it. Model:5-12. */
@Serializable
enum class EffectFamily(val title: String) {
    @SerialName("key") Key("Background"),
    @SerialName("camera") Camera("Camera"),
    @SerialName("color") Color("Color & tone"),
    @SerialName("quantization") Quantization("Quantization"),
    @SerialName("texture") Texture("Film texture"),
    @SerialName("frame") Frame("Frame"),
    @SerialName("time") Time("Time");

    /** The place of the family in [VideoEffectSettings.ordered]. */
    val order: Int get() = ordinal
}

/** Model:13. `lite` is the recorder's emergency configuration. */
@Serializable
enum class VideoEffectQuality {
    @SerialName("full") Full,
    @SerialName("lite") Lite,
}

/** Model:15-24. */
enum class NightFixMode(val rawValue: String, val radius: Int, val guideLimit: Double, val explanation: String) {
    Fast("Fast", 0, 512.0, "Single-frame cleanup. Preview and export use this result, with no extra preparation."),
    Medium("Medium", 1, 256.0, "Combines 3 motion-aligned frames for cleaner noise with less waiting."),
    Precise("Precise", 4, 512.0, "Combines 9 motion-aligned frames for the strongest temporal cleanup. Takes longer to prepare."),
}

/** Where an effect can be used: while recording, in the editor, or both. Model:26. */
@Serializable
enum class EffectMode {
    @SerialName("live") Live,
    @SerialName("edit") Edit,
    @SerialName("both") Both,
}

/** Model:27-32. */
enum class RGBChannel(val rawValue: String) {
    Red("red"),
    Green("green"),
    Blue("blue");

    /** As the pads label it: "Red". */
    val channelName: String get() = rawValue.replaceFirstChar { it.uppercase() }
    val x: String get() = rawValue + "X"
    val y: String get() = rawValue + "Y"
}

/**
 * The 24 effects. [rawValue] is the name saved in a project; [shaderIndex] is
 * the number the shader switches on, a stable interface that adding or
 * reordering catalogue entries must not change (10 was a removed rotation
 * effect). Model:34-54.
 */
@Serializable
enum class VideoEffectID(val rawValue: String, val shaderIndex: Int) {
    @SerialName("rgbOffset") RgbOffset("rgbOffset", 23),
    @SerialName("negativeStrobe") NegativeStrobe("negativeStrobe", 24),
    @SerialName("nightFix") NightFix("nightFix", 21),
    @SerialName("colorCorrection") ColorCorrection("colorCorrection", 20),
    @SerialName("silentTint") SilentTint("silentTint", 0),
    @SerialName("threeStrip") ThreeStrip("threeStrip", 1),
    @SerialName("bleachBypass") BleachBypass("bleachBypass", 2),
    @SerialName("dayForNight") DayForNight("dayForNight", 3),
    @SerialName("colorIsolation") ColorIsolation("colorIsolation", 4),
    @SerialName("noir") Noir("noir", 5),
    @SerialName("expressionism") Expressionism("expressionism", 6),
    @SerialName("quantization") Quantization("quantization", 7),
    @SerialName("dollyZoom") DollyZoom("dollyZoom", 8),
    @SerialName("crashZoom") CrashZoom("crashZoom", 9),
    @SerialName("kaleidoscope") Kaleidoscope("kaleidoscope", 22),
    @SerialName("widescreen") Widescreen("widescreen", 11),
    @SerialName("freezeFrame") FreezeFrame("freezeFrame", 12),
    @SerialName("stepPrinting") StepPrinting("stepPrinting", 13),
    @SerialName("undercranking") Undercranking("undercranking", 14),
    @SerialName("halation") Halation("halation", 15),
    @SerialName("sixteenMM") SixteenMM("sixteenMM", 16),
    @SerialName("superEight") SuperEight("superEight", 17),
    @SerialName("chromaKey") ChromaKey("chromaKey", 18),
    @SerialName("autoBackground") AutoBackground("autoBackground", 19);

    val descriptor: VideoEffectDescriptor get() = VideoEffectCatalog.descriptor(this)

    companion object {
        /** The effect saved under a name, or null for one this version does not have. */
        fun of(rawValue: String): VideoEffectID? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * One value of a parameter (Model:64-74).
 *
 * Written as Swift writes an enum with values: an object with the case's name
 * as its one key and the value under `_0`: `{"number":{"_0":0.5}}`,
 * `{"toggle":{"_0":true}}`, `{"choice":{"_0":"Amber"}}`,
 * `{"color":{"_0":{"red":…,"green":…,"blue":…}}}`, `{"media":{"_0":"a.png"}}`
 * and `{"media":{}}` for no file.
 */
@Serializable(with = EffectValueSerializer::class)
sealed interface EffectValue {
    data class Number(val value: Double) : EffectValue
    data class Toggle(val value: Boolean) : EffectValue
    data class Choice(val value: String) : EffectValue
    data class Color(val value: EffectRGB) : EffectValue

    /** A file of the project, by name; null while none is chosen. */
    data class Media(val file: String?) : EffectValue

    /** Numbers and colours move towards [other]; everything else holds until [fraction] reaches 1. */
    fun interpolated(other: EffectValue, fraction: Double): EffectValue {
        val t = minOf(1.0, maxOf(0.0, fraction))
        return when {
            this is Number && other is Number -> Number(value + (other.value - value) * t)
            this is Color && other is Color -> Color(
                EffectRGB(
                    value.red + (other.value.red - value.red) * t,
                    value.green + (other.value.green - value.green) * t,
                    value.blue + (other.value.blue - value.blue) * t,
                ),
            )
            else -> if (t < 1) this else other
        }
    }
}

internal object EffectValueSerializer : KSerializer<EffectValue> {
    @Serializable
    private class Number(@SerialName("_0") val value: Double)

    @Serializable
    private class Toggle(@SerialName("_0") val value: Boolean)

    @Serializable
    private class Choice(@SerialName("_0") val value: String)

    @Serializable
    private class Color(@SerialName("_0") val value: EffectRGB)

    @Serializable
    private class Media(@SerialName("_0") val file: String? = null)

    @Serializable
    private class Cases(
        val number: Number? = null,
        val toggle: Toggle? = null,
        val choice: Choice? = null,
        val color: Color? = null,
        val media: Media? = null,
    )

    override val descriptor: SerialDescriptor = Cases.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EffectValue) = Cases.serializer().serialize(
        encoder,
        when (value) {
            is EffectValue.Number -> Cases(number = Number(value.value))
            is EffectValue.Toggle -> Cases(toggle = Toggle(value.value))
            is EffectValue.Choice -> Cases(choice = Choice(value.value))
            is EffectValue.Color -> Cases(color = Color(value.value))
            is EffectValue.Media -> Cases(media = Media(value.file))
        },
    )

    override fun deserialize(decoder: Decoder): EffectValue {
        val cases = Cases.serializer().deserialize(decoder)
        if (listOfNotNull(cases.number, cases.toggle, cases.choice, cases.color, cases.media).size != 1) {
            throw SerializationException("An effect value has exactly one case")
        }
        return cases.number?.let { EffectValue.Number(it.value) }
            ?: cases.toggle?.let { EffectValue.Toggle(it.value) }
            ?: cases.choice?.let { EffectValue.Choice(it.value) }
            ?: cases.color?.let { EffectValue.Color(it.value) }
            ?: EffectValue.Media(cases.media!!.file)
    }
}

/**
 * What kind of control a parameter is, with its bounds and default (Model:75-103).
 *
 * Written as Swift writes it, the case's name over its labelled values:
 * `{"slider":{"min":0,"max":1,"defaultValue":1,"step":0.01}}`,
 * `{"toggle":{"defaultValue":true}}`, `{"stepper":{"values":[…],"defaultValue":8}}`,
 * `{"color":{"defaultValue":{…}}}`, `{"picker":{"options":[…],"defaultValue":"Amber"}}`,
 * `{"media":{"image":true,"video":false}}`.
 */
@Serializable(with = EffectParameterKindSerializer::class)
sealed interface EffectParameterKind {
    @Serializable
    data class Slider(val min: Double, val max: Double, val defaultValue: Double, val step: Double) : EffectParameterKind

    @Serializable
    data class Toggle(val defaultValue: Boolean) : EffectParameterKind

    @Serializable
    data class Stepper(val values: List<Double>, val defaultValue: Double) : EffectParameterKind

    @Serializable
    data class Color(val defaultValue: EffectRGB) : EffectParameterKind

    @Serializable
    data class Picker(val options: List<String>, val defaultValue: String) : EffectParameterKind

    @Serializable
    data class Media(val image: Boolean, val video: Boolean) : EffectParameterKind

    /** The value a new effect starts with (`defaultValue` in the original, a name the fields above already use). */
    val default: EffectValue
        get() = when (this) {
            is Slider -> EffectValue.Number(defaultValue)
            is Stepper -> EffectValue.Number(defaultValue)
            is Toggle -> EffectValue.Toggle(defaultValue)
            is Color -> EffectValue.Color(defaultValue)
            is Picker -> EffectValue.Choice(defaultValue)
            is Media -> EffectValue.Media(null)
        }

    /** Sliders and colours move between keyframes; every other kind holds. */
    val continuous: Boolean get() = this is Slider || this is Color

    /** Whether [value] is one this control can hold. */
    fun accepts(value: EffectValue): Boolean = when {
        this is Slider && value is EffectValue.Number -> value.value.isFinite() && value.value in min..max
        this is Stepper && value is EffectValue.Number -> values.contains(value.value)
        this is Toggle && value is EffectValue.Toggle -> true
        this is Color && value is EffectValue.Color -> value.value.valid
        this is Picker && value is EffectValue.Choice -> options.contains(value.value)
        this is Media && value is EffectValue.Media -> value.file?.let { file ->
            val extension = file.substringAfterLast('.', "").lowercase()
            isProjectFileName(file) &&
                (image && extension in IMAGE_EXTENSIONS || video && extension in VIDEO_EXTENSIONS)
        } ?: true
        else -> false
    }

    companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "heic", "heif", "tif", "tiff", "webp")
        val VIDEO_EXTENSIONS = listOf("mov", "mp4", "m4v")
    }
}

/** A bare file name inside the project's folder: no path, not hidden, no backslash. Model:98, :288. */
internal fun isProjectFileName(name: String): Boolean =
    name.isNotEmpty() && !name.contains('/') && !name.startsWith(".") && !name.contains("\\")

internal object EffectParameterKindSerializer : KSerializer<EffectParameterKind> {
    @Serializable
    private class Cases(
        val slider: EffectParameterKind.Slider? = null,
        val toggle: EffectParameterKind.Toggle? = null,
        val stepper: EffectParameterKind.Stepper? = null,
        val color: EffectParameterKind.Color? = null,
        val picker: EffectParameterKind.Picker? = null,
        val media: EffectParameterKind.Media? = null,
    )

    override val descriptor: SerialDescriptor = Cases.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EffectParameterKind) = Cases.serializer().serialize(
        encoder,
        when (value) {
            is EffectParameterKind.Slider -> Cases(slider = value)
            is EffectParameterKind.Toggle -> Cases(toggle = value)
            is EffectParameterKind.Stepper -> Cases(stepper = value)
            is EffectParameterKind.Color -> Cases(color = value)
            is EffectParameterKind.Picker -> Cases(picker = value)
            is EffectParameterKind.Media -> Cases(media = value)
        },
    )

    override fun deserialize(decoder: Decoder): EffectParameterKind {
        val cases = Cases.serializer().deserialize(decoder)
        val all = listOfNotNull(cases.slider, cases.toggle, cases.stepper, cases.color, cases.picker, cases.media)
        if (all.size != 1) throw SerializationException("An effect parameter kind has exactly one case")
        return all.single()
    }
}

/** A value a parameter has at a source second. Model:104-108. */
@Serializable
data class EffectKeyframe(val id: String = newID(), val time: Double, val value: EffectValue)

/**
 * One control of an effect: its catalogue entry ([id], [title], [kind],
 * [animatable]) and what the person set ([value], [keyframes]). Model:109-147.
 */
@Serializable
data class EffectParameter(
    val id: String,
    val title: String,
    val kind: EffectParameterKind,
    val value: EffectValue = kind.default,
    val keyframes: List<EffectKeyframe> = emptyList(),
    val animatable: Boolean = true,
) {
    /** A catalogue entry at its default. */
    constructor(id: String, title: String, kind: EffectParameterKind, animatable: Boolean) :
        this(id, title, kind, kind.default, emptyList(), animatable)

    /**
     * The value at a source second: the plain value without keyframes, the
     * first key before it, the last after it. Between two keys a continuous
     * control interpolates and any other holds the earlier key. The two angles
     * of the Kaleidoscope take the short way round.
     */
    fun value(time: Double): EffectValue {
        val first = keyframes.firstOrNull() ?: return value
        if (!(time > first.time)) return first.value
        for ((a, b) in keyframes.zipWithNext()) {
            if (!(time < b.time)) continue
            val fraction = (time - a.time) / (b.time - a.time)
            val start = a.value
            val end = b.value
            if ((id == "splitAngle" || id == "sourceAngle") && start is EffectValue.Number && end is EffectValue.Number) {
                val difference = end.value - start.value
                val travel = if (abs(difference) > 180) difference - 360.0.withSign(difference) else difference
                return EffectValue.Number(wrappedDegrees(start.value + travel * fraction))
            }
            return if (kind.continuous) a.value.interpolated(b.value, fraction) else a.value
        }
        return keyframes.last().value
    }

    /**
     * This parameter with a key at [time]; a key within a sixty-thousandth of
     * a second of it is replaced. Unchanged when the parameter cannot be
     * animated, or the time or the value is not acceptable.
     */
    fun withKeyframe(time: Double, value: EffectValue): EffectParameter {
        if (!(animatable && time.isFinite() && time >= 0 && kind.accepts(value))) return this
        val kept = keyframes.filterNot { abs(it.time - time) < 1.0 / 60000 }
        return copy(keyframes = (kept + EffectKeyframe(time = time, value = value)).sortedBy { it.time })
    }

    val valid: Boolean
        get() = kind.accepts(value) && (animatable || keyframes.isEmpty()) && keyframes.size <= 100_000 &&
            keyframes.map { it.id }.toSet().size == keyframes.size &&
            keyframes.all { it.time.isFinite() && it.time in 0.0..VideoEffectSettings.MAXIMUM_TIME && kind.accepts(it.value) } &&
            keyframes.zipWithNext().all { (earlier, later) -> earlier.time < later.time }

    companion object {
        /** An angle brought into −180…180, with 180 itself read as −180. */
        fun wrappedDegrees(value: Double): Double = value - 360 * floor((value + 180) / 360)
    }
}

/** A catalogue entry: one effect with its controls at their defaults. Model:148-156. */
data class VideoEffectDescriptor(
    val id: VideoEffectID,
    val displayName: String,
    val historyNote: String,
    val family: EffectFamily,
    val mode: EffectMode,
    val parameters: List<EffectParameter>,
) {
    /** Image effects mix with their input; structural time effects and camera moves do not. */
    val supportsIntensity: Boolean get() = family != EffectFamily.Time && family != EffectFamily.Camera
}

/** A palette of the Quantization effect with the luma thresholds between its colours. VideoEffectMath.swift:81-85. */
@Serializable
data class EffectPalette(val colors: List<EffectRGB>, val thresholds: List<Double>) {
    val valid: Boolean
        get() = colors.size in 2..16 && colors.all { it.valid } && thresholds.size == colors.size - 1 &&
            thresholds.all { it.isFinite() && it in 0.0..1.0 } &&
            thresholds.zipWithNext().all { (earlier, later) -> earlier <= later }
}

/**
 * One effect on a clip (Model:162-361).
 *
 * [start] and [end] are source seconds: the effect is on from [start] up to,
 * not including, [end]. [parameters] always holds the catalogue's controls for
 * [kind], with "intensity" first where the effect has one. [motion] is an A/B
 * move, of the Color Correction only; while set it decides the value of every
 * control it names. [algorithm] says which rendering a recipe was made with:
 * absent in old recipes, 1 the single-frame one, 2 the temporal matte of Color
 * Isolation and Chroma Key. [strobeTimeMap] and [phaseOffset] are the playback
 * clock of a Negative Strobe as it was when the effect was applied.
 */
@Serializable(with = VideoEffectSettingsSerializer::class)
data class VideoEffectSettings(
    val kind: VideoEffectID,
    val parameters: List<EffectParameter>,
    val id: String = newID(),
    val enabled: Boolean = true,
    val version: Int = 1,
    val start: Double = 0.0,
    val end: Double = MAXIMUM_TIME,
    /** Persisted analysis and palette assets are immutable, project-local files. */
    val analysisFile: String? = null,
    val analysisDataFile: String? = null,
    val capturedEffectID: String? = null,
    val palette: List<EffectRGB>? = null,
    val lockedPalette: EffectPalette? = null,
    val motion: EffectAnimation? = null,
    /** Absent in legacy recipes; preserves their rendering. */
    val algorithm: Int? = null,
    /** Playback seconds at authoring time for sped-up or cut source material. */
    val strobeTimeMap: List<StrobeTimePoint>? = null,
    /** Elapsed time carried into a captured camera move or an editor strobe across clips. */
    val phaseOffset: Double? = null,
    /** Source-time manual return of a captured Crash Zoom. */
    val releaseTime: Double? = null,
) {
    /**
     * A new effect at the catalogue's defaults. New Color Isolation and Chroma
     * Key recipes are made with the temporal matte, [algorithm] 2, and every
     * other with 1 (Model:200-205).
     */
    constructor(kind: VideoEffectID, start: Double = 0.0, end: Double = MAXIMUM_TIME) : this(
        kind = kind,
        parameters = defaultParameters(kind),
        start = start,
        end = end,
        algorithm = if (kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey) 2 else 1,
    )

    val analysisID: String get() = capturedEffectID ?: id
    val captureIdentity: VideoEffectSettings get() = copy(id = analysisID, capturedEffectID = null)

    val nightFixMode: NightFixMode get() = NightFixMode.entries.firstOrNull { it.rawValue == choice("mode") } ?: NightFixMode.Fast

    val usesTemporalMatte: Boolean get() = algorithm == 2 && (kind == VideoEffectID.ColorIsolation || kind == VideoEffectID.ChromaKey)
    val usesModernPersonMask: Boolean get() = algorithm == 1 || usesTemporalMatte
    val usesAutoMaskStream: Boolean get() = kind == VideoEffectID.AutoBackground && algorithm == 1

    /** Whether the effect needs something computed from the footage before it can be drawn. Model:181-190. */
    val requiresOfflineAnalysis: Boolean
        get() {
            if (!enabled) return false
            return when (kind) {
                VideoEffectID.NightFix -> nightFixMode != NightFixMode.Fast
                VideoEffectID.Quantization, VideoEffectID.AutoBackground, VideoEffectID.DollyZoom -> true
                VideoEffectID.CrashZoom -> toggle("trackFace", start) || parameters.any { it.id == "trackFace" && it.keyframes.isNotEmpty() }
                VideoEffectID.ColorIsolation, VideoEffectID.ChromaKey -> usesModernPersonMask
                else -> false
            }
        }

    /** On, inside its range, and, where it has an intensity, above zero. Model:194-196. */
    fun isActive(time: Double): Boolean =
        enabled && time >= start && time < end && (!kind.descriptor.supportsIntensity || number("intensity", time) > 0)

    /** A control's value at a source second: the motion's where it names the control, else the parameter's. Model:233-236. */
    fun value(id: String, time: Double = 0.0): EffectValue? {
        motion?.let { if (it.a.values[id] != null) return EffectValue.Number(it.value(id, it.fraction(time))) }
        return parameters.firstOrNull { it.id == id }?.value(time)
    }

    fun number(id: String, time: Double = 0.0): Double = (value(id, time) as? EffectValue.Number)?.value ?: 0.0
    fun toggle(id: String, time: Double = 0.0): Boolean = (value(id, time) as? EffectValue.Toggle)?.value ?: false
    fun choice(id: String, time: Double = 0.0): String = (value(id, time) as? EffectValue.Choice)?.value ?: ""
    fun color(id: String, time: Double = 0.0): EffectRGB = (value(id, time) as? EffectValue.Color)?.value ?: EffectRGB(0.0, 0.0, 0.0)

    /** This effect with a control set; unchanged when it has no such control or the control cannot hold the value. Model:241-244. */
    fun with(id: String, value: EffectValue): VideoEffectSettings {
        val index = parameters.indexOfFirst { it.id == id }
        if (index < 0 || !parameters[index].kind.accepts(value)) return this
        return copy(parameters = parameters.toMutableList().also { it[index] = it[index].copy(value = value) })
    }

    /** This effect with a key on one control, by [EffectParameter.withKeyframe]. */
    fun withKeyframe(id: String, time: Double, value: EffectValue): VideoEffectSettings =
        copy(parameters = parameters.map { if (it.id == id) it.withKeyframe(time, value) else it })

    /**
     * This effect without a background's media of the other kind than its background at [time]: a picture under a
     * Video background or a movie under an Image one is let go, by a key there when the media or the background
     * moves. Model:252-262.
     */
    fun sanitizedBackgroundMedia(time: Double): VideoEffectSettings {
        val mode = choice("background", time)
        if (mode != "Image" && mode != "Video") return this
        val file = (value("media", time) as? EffectValue.Media)?.file ?: return this
        val media = parameters.firstOrNull { it.id == "media" } ?: return this
        val video = file.substringAfterLast('.', "").lowercase() in listOf("mov", "mp4", "m4v")
        if ((mode == "Video") == video) return this
        val moving = media.keyframes.isNotEmpty() || parameters.firstOrNull { it.id == "background" }?.keyframes?.isNotEmpty() == true
        return if (moving) withKeyframe("media", time, EffectValue.Media(null)) else with("media", EffectValue.Media(null))
    }

    /** What a recording keeps of a look that was showing its matte or its original. Model:245-251. */
    val recordingResult: VideoEffectSettings
        get() = copy(parameters = parameters.map { if (it.id == "view") it.copy(value = EffectValue.Choice("Result"), keyframes = emptyList()) else it })

    /** Every project file the effect names, once each, sorted. Model:263-268. */
    val mediaFiles: List<String>
        get() {
            val files = listOfNotNull(analysisFile, analysisDataFile).toMutableList()
            for (p in parameters) for (v in listOf(p.value) + p.keyframes.map { it.value }) (v as? EffectValue.Media)?.file?.let { files += it }
            return files.toSet().sorted()
        }

    /** The numbers of every control at a source second: the pose a motion of this effect moves. EffectAnimation.swift:245-254. */
    fun correctionPose(time: Double): EffectPose =
        EffectPose(parameters.mapNotNull { p -> (p.value(time) as? EffectValue.Number)?.let { p.id to it.value } }.toMap())

    /** Whether a saved project may hold this effect. Model:269-289. */
    val valid: Boolean
        get() {
            val reference = defaultParameters(kind)
            motion?.let { motion ->
                val accepted = kind == VideoEffectID.ColorCorrection && motion.valid(correctionPose(start)) { p ->
                    reference.all { it.kind.accepts(EffectValue.Number(p[it.id])) }
                }
                if (!accepted) return false
            }
            return (algorithm == null || algorithm == 1 || usesTemporalMatte) && version == 1 &&
                start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= MAXIMUM_TIME &&
                parameters.size == reference.size && parameters.map { it.id }.toSet().size == parameters.size &&
                parameters.all { p -> p.valid && reference.any { it.id == p.id && it.kind == p.kind && it.animatable == p.animatable } } &&
                (strobeTimeMap?.let { points ->
                    kind == VideoEffectID.NegativeStrobe && points.size >= 2 && points.size <= 100_000 &&
                        points.all { it.source.isFinite() && it.elapsed.isFinite() && it.elapsed in 0.0..MAXIMUM_TIME } &&
                        points.zipWithNext().all { (earlier, later) -> earlier.source < later.source && earlier.elapsed <= later.elapsed }
                } ?: true) &&
                (releaseTime?.let { kind == VideoEffectID.CrashZoom && it.isFinite() && it >= start - (phaseOffset ?: 0.0) && it < end } ?: true) &&
                (phaseOffset?.let {
                    (kind == VideoEffectID.CrashZoom || kind == VideoEffectID.NegativeStrobe) && it.isFinite() && it in 0.0..MAXIMUM_TIME
                } ?: true) &&
                (lockedPalette?.valid ?: true) &&
                (palette?.let { it.size in 2..16 && it.all(EffectRGB::valid) } ?: true) &&
                mediaFiles.all(::isProjectFileName)
        }

    /**
     * Whether a Negative Strobe shows the negative at a source second. The
     * clock is playback seconds since the effect began: through
     * [strobeTimeMap] where the footage was sped up or cut when the effect was
     * applied, plus [phaseOffset]; [holdTime] replaces it on a held frame. The
     * rate is limited to half of [fps], one change of polarity per frame.
     *
     * Integrate held rate keys. Changing speed cannot rewind the phase, and
     * seeking to a frame produces exactly the same polarity as playing it.
     *
     * Model:290-321.
     */
    fun strobeShowsNegative(time: Double, fps: Double, holdTime: Double? = null): Boolean {
        fun elapsed(t: Double): Double {
            val points = strobeTimeMap
            val first = points?.firstOrNull() ?: return maxOf(0.0, t - start)
            val last = points.last()
            if (t <= first.source) return first.elapsed
            if (t >= last.source) return last.elapsed
            var lo = 0
            var hi = points.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (points[mid].source <= t) lo = mid else hi = mid
            }
            val a = points[lo]
            val b = points[hi]
            return a.elapsed + (b.elapsed - a.elapsed) * (t - a.source) / (b.source - a.source)
        }
        val offset = phaseOffset ?: 0.0
        val clock = offset + (holdTime ?: elapsed(time))
        if (toggle("timedBurst") && clock >= number("burstLength") - 1e-8) return false
        val frequency = parameters.firstOrNull { it.id == "frequency" } ?: return false
        val ceiling = maxOf(0.5, fps * 0.5)
        fun rate(value: EffectValue): Double = (value as? EffectValue.Number)?.let { minOf(ceiling, it.value) } ?: 8.0
        var hz = rate(frequency.value(start))
        var last = 0.0
        var cycles = 0.0
        for (key in frequency.keyframes) {
            if (!(key.time > start)) continue
            if (key.time > time) break
            val next = offset + elapsed(key.time)
            cycles += (next - last) * hz
            last = next
            hz = rate(key.value)
        }
        cycles += (clock - last) * hz
        return floor(cycles * 2 + 1e-7).rem(2.0) == 1.0
    }

    /**
     * Captured moves may be retimed in the editor. Never leave their old end
     * cutting off an extended push/hold, and never overlap a later trigger.
     * Model:325-329.
     */
    fun retimeCapturedCrash(previous: VideoEffectSettings, limit: Double): VideoEffectSettings {
        val timing = setOf("speed", "hold", "return")
        if (parameters.filter { it.id in timing } == previous.parameters.filter { it.id in timing }) return this
        return refreshCapturedCrashEnd(limit)
    }

    /** Model:330-341. */
    fun refreshCapturedCrashEnd(limit: Double): VideoEffectSettings {
        val offset = phaseOffset
        if (!(kind == VideoEffectID.CrashZoom && offset != null && limit > start)) return this
        fun maximum(id: String): Double {
            val p = parameters.firstOrNull { it.id == id } ?: return 0.0
            return (listOf(p.value) + p.keyframes.map { it.value }).mapNotNull { (it as? EffectValue.Number)?.value }.maxOrNull() ?: 0.0
        }
        val push = maximum("speed")
        val hold = maximum("hold")
        val origin = start - offset
        val allReturn = parameters.firstOrNull { it.id == "return" }
            ?.let { p -> (listOf(p.value) + p.keyframes.map { it.value }).all { it == EffectValue.Toggle(true) } } ?: false
        val desired = releaseTime?.let { it + push } ?: if (allReturn) origin + 2 * push + 0.08 + hold else limit
        val newEnd = maxOf(start + minOf(1.0 / 30, limit - start), minOf(limit, desired))
        return copy(end = newEnd, releaseTime = releaseTime?.takeUnless { it >= newEnd })
    }

    companion object {
        /** A week of seconds: the latest second an effect, a keyframe or a strobe clock may name. */
        const val MAXIMUM_TIME = 604800.0

        /** The catalogue's controls for [kind], with "intensity" first where the effect has one. Model:203-204. */
        fun defaultParameters(kind: VideoEffectID): List<EffectParameter> {
            val descriptor = kind.descriptor
            return if (descriptor.supportsIntensity) listOf(INTENSITY) + descriptor.parameters else descriptor.parameters
        }

        private val INTENSITY = EffectParameter("intensity", "Intensity", EffectParameterKind.Slider(0.0, 1.0, 1.0, 0.01))

        /** What a take keeps of the effects recorded with it: those that began before it ended, cut to its length. Model:342-349. */
        fun capturedRecipe(effects: List<VideoEffectSettings>, duration: Double): List<VideoEffectSettings> = effects.mapNotNull { effect ->
            if (!(effect.start < duration)) return@mapNotNull null
            val end = minOf(duration, effect.end)
            effect.copy(end = end, releaseTime = effect.releaseTime?.takeUnless { it >= end }).takeIf { it.end > it.start }
        }

        /**
         * The applied list in the fixed order of the families: Key, Camera,
         * Color, Quantization, Texture, Frame, Time. Inside a family the order
         * in which the effects were added is kept, except that Night fix comes
         * before everything, the Kaleidoscope last among the camera effects,
         * and the Color Correction before the colour looks. Model:350-360.
         */
        fun ordered(effects: List<VideoEffectSettings>): List<VideoEffectSettings> =
            effects.withIndex().sortedWith { a, b ->
                val x = a.value.kind
                val y = b.value.kind
                val fx = x.descriptor.family.order
                val fy = y.descriptor.family.order
                when {
                    (x == VideoEffectID.NightFix) != (y == VideoEffectID.NightFix) -> if (x == VideoEffectID.NightFix) -1 else 1
                    fx != fy -> fx.compareTo(fy)
                    (x == VideoEffectID.Kaleidoscope) != (y == VideoEffectID.Kaleidoscope) -> if (y == VideoEffectID.Kaleidoscope) -1 else 1
                    // Primary balance precedes creative color looks, regardless of add order.
                    (x == VideoEffectID.ColorCorrection) != (y == VideoEffectID.ColorCorrection) -> if (x == VideoEffectID.ColorCorrection) -1 else 1
                    else -> a.index.compareTo(b.index)
                }
            }.map { it.value }

        /**
         * How much faster the footage plays at a source second because of the
         * effects on it: the product of the "speed" of every Undercranking and
         * Step Printing that is on there. VideoEffectTiming.swift:17-22.
         */
        fun sequenceRate(effects: List<VideoEffectSettings>, time: Double): Double =
            effects.filter { it.enabled && it.start <= time && it.end > time }.fold(1.0) { result, effect ->
                if (effect.kind == VideoEffectID.Undercranking || effect.kind == VideoEffectID.StepPrinting) result * effect.number("speed", time) else result
            }

        /**
         * Whether the effects silence the footage's own sound at a source
         * second: an Undercranking set to mute, or, on a held frame
         * ([frozen]), a Freeze Frame whose audio is "Cut".
         * VideoEffectTiming.swift:24-28.
         */
        fun mutesSourceAudio(effects: List<VideoEffectSettings>, time: Double, frozen: Boolean = false): Boolean {
            if (frozen && effects.firstOrNull { it.kind == VideoEffectID.FreezeFrame }?.choice("audio", time) == "Cut") return true
            return effects.any { it.enabled && it.kind == VideoEffectID.Undercranking && it.start <= time && it.end > time && it.toggle("mute", time) }
        }
    }
}

/**
 * Reads and writes one effect with the original's field names. On reading,
 * bounds, labels and supported choices belong to the installed catalogue: old
 * projects retain values and animation when the catalogue gains a choice or
 * enables animation; they cannot redefine the allowed bounds. A control the
 * catalogue has gained since is added at its default; one it no longer has is
 * kept as saved, which [VideoEffectSettings.valid] then refuses. Model:206-232.
 */
internal object VideoEffectSettingsSerializer : KSerializer<VideoEffectSettings> {
    @Serializable
    private class Stored(
        val id: String,
        val kind: VideoEffectID,
        val enabled: Boolean,
        val version: Int,
        val start: Double,
        val end: Double,
        val parameters: List<EffectParameter>,
        val analysisFile: String? = null,
        val analysisDataFile: String? = null,
        val capturedEffectID: String? = null,
        val palette: List<EffectRGB>? = null,
        val lockedPalette: EffectPalette? = null,
        val releaseTime: Double? = null,
        val strobeTimeMap: List<StrobeTimePoint>? = null,
        val phaseOffset: Double? = null,
        val motion: EffectAnimation? = null,
        val algorithm: Int? = null,
    )

    override val descriptor: SerialDescriptor = Stored.serializer().descriptor

    override fun serialize(encoder: Encoder, value: VideoEffectSettings) = Stored.serializer().serialize(
        encoder,
        Stored(
            value.id, value.kind, value.enabled, value.version, value.start, value.end, value.parameters,
            value.analysisFile, value.analysisDataFile, value.capturedEffectID, value.palette, value.lockedPalette,
            value.releaseTime, value.strobeTimeMap, value.phaseOffset, value.motion, value.algorithm,
        ),
    )

    override fun deserialize(decoder: Decoder): VideoEffectSettings {
        val stored = Stored.serializer().deserialize(decoder)
        val reference = VideoEffectSettings.defaultParameters(stored.kind)
        val parameters = stored.parameters.map { old ->
            reference.firstOrNull { it.id == old.id }?.copy(value = old.value, keyframes = old.keyframes) ?: old
        } + reference.filter { p -> stored.parameters.none { it.id == p.id } }
        return VideoEffectSettings(
            stored.kind, parameters, stored.id, stored.enabled, stored.version, stored.start, stored.end,
            stored.analysisFile, stored.analysisDataFile, stored.capturedEffectID, stored.palette, stored.lockedPalette,
            stored.motion, stored.algorithm, stored.strobeTimeMap, stored.phaseOffset, stored.releaseTime,
        )
    }
}

/**
 * The list of effects of a clip or a take, as a saved project holds it. Use it
 * on the field: `@Serializable(with = SupportedVideoEffects::class)`.
 *
 * Unknown catalogue IDs are ignored at collection boundaries. Known effects
 * still decode and validate normally: corrupt recognized settings are not
 * hidden. Model:363-387; docs/VIDEO-EFFECTS.md, "Crop rotation".
 */
object SupportedVideoEffects : KSerializer<List<VideoEffectSettings>> {
    private val list = ListSerializer(VideoEffectSettings.serializer())
    override val descriptor: SerialDescriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: List<VideoEffectSettings>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<VideoEffectSettings> {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Video effects are read from JSON")
        val items = json.decodeJsonElement() as? JsonArray ?: throw SerializationException("Video effects are a list")
        return items.mapNotNull { item ->
            val name = kindOf(item) ?: throw SerializationException("A video effect names its kind")
            if (VideoEffectID.of(name) == null) null else json.json.decodeFromJsonElement(VideoEffectSettings.serializer(), item)
        }
    }

    private fun kindOf(item: JsonElement): String? =
        ((item as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
}
