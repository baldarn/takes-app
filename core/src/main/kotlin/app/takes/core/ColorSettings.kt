package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** The built-in looks. Their rendering is not part of the model. */
@Serializable
enum class ColorLook(val title: String) {
    @SerialName("natural") Natural("Natural"),
    @SerialName("warm") Warm("Warm"),
    @SerialName("cool") Cool("Cool shadows"),
    @SerialName("cinema") Cinema("Cinema"),
    @SerialName("mono") Mono("Mono"),
    @SerialName("portrait") Portrait("Portrait"),
    @SerialName("daylight") Daylight("Daylight"),
    @SerialName("goldenHour") GoldenHour("Golden hour"),
    @SerialName("mutedFilm") MutedFilm("Muted film"),
    @SerialName("richContrast") RichContrast("Rich contrast"),
    @SerialName("pastel") Pastel("Pastel"),
    @SerialName("night") Night("Night"),
    @SerialName("silver") Silver("Silver"),
    @SerialName("ink") Ink("Ink"),
}

/** The encoding a LUT expects its input in. */
@Serializable
enum class LUTInput(val title: String) {
    @SerialName("rec709") Rec709("Rec.709"),
    @SerialName("appleLog") AppleLog("Apple Log"),
    @SerialName("appleLog2") AppleLog2("Apple Log 2"),
}

/** A .cube file kept with the project. */
@Serializable
data class ImportedLUT(val filename: String, val name: String, val input: LUTInput = LUTInput.Rec709) {
    val valid: Boolean
        get() = filename.startsWith("effect-lut-") && filename.endsWith(".cube") && filename.length < 160 &&
            filename == File(filename).name && !filename.contains("\\") && name.isNotEmpty() && name.length <= 120
}

/** A grade: a look or an imported LUT at a strength, plus balance corrections. */
@Serializable
data class ColorSettings(
    val id: String = newID(),
    val look: ColorLook = ColorLook.Natural,
    val amount: Double = 1.0,
    val imported: ImportedLUT? = null,
    val exposure: Double? = null,
    val highlights: Double? = null,
    val localRecovery: Double? = null,
    val warmth: Double? = null,
    val tint: Double? = null,
    val motion: EffectAnimation? = null,
) {
    val hasBalance: Boolean
        get() = (exposure ?: 0.0) != 0.0 || (highlights ?: 0.0) != 0.0 || (localRecovery ?: 0.0) != 0.0 ||
            (warmth ?: 0.0) != 0.0 || (tint ?: 0.0) != 0.0

    val title: String get() = imported?.name ?: if (look == ColorLook.Natural && hasBalance) "Color balance" else look.title

    /** A zero-strength look remains editable; only an empty grade is removed. */
    val isIdentity: Boolean get() = motion == null && !hasBalance && look == ColorLook.Natural && imported == null

    /** The ranges of the grade itself. A motion is checked with [EffectAnimation.valid] against [pose]. */
    val valid: Boolean
        get() = amount.isFinite() && amount in 0.0..1.0 && within(exposure, -4.0, 4.0) && within(highlights, -1.0, 1.0) &&
            within(localRecovery, 0.0, 1.0) && within(warmth, -1.0, 1.0) && within(tint, -1.0, 1.0) && (imported?.valid ?: true)

    val pose: EffectPose
        get() = EffectPose(
            mapOf(
                "amount" to amount, "exposure" to (exposure ?: 0.0), "highlights" to (highlights ?: 0.0),
                "localRecovery" to (localRecovery ?: 0.0), "warmth" to (warmth ?: 0.0), "tint" to (tint ?: 0.0),
            ),
            look, imported,
        )

    /** This grade fixed at [pose], no longer moving. */
    fun applying(pose: EffectPose): ColorSettings = ColorSettings(
        id, pose.look ?: ColorLook.Natural, pose["amount"], pose.lut, pose["exposure"], pose["highlights"],
        pose["localRecovery"], pose["warmth"], pose["tint"],
    )

    /** Every LUT file this grade needs, including those only an end of its motion uses. */
    val lutAssets: List<ImportedLUT> get() = listOfNotNull(imported, motion?.a?.lut, motion?.b?.lut)

    private fun within(value: Double?, low: Double, high: Double): Boolean = (value ?: 0.0).let { it.isFinite() && it in low..high }
}
