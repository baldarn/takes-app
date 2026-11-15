package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The four title styles of older projects. New titles use [TitleDesign]; see [TitleDesign.legacy]. */
@Serializable
enum class TextStyle {
    @SerialName("clean") Clean,
    @SerialName("bold") Bold,
    @SerialName("box") Box,
    @SerialName("highlight") Highlight,
}

/** The six title colors of older projects, as sRGB components 0–1. */
@Serializable
enum class TextColor(val red: Double, val green: Double, val blue: Double) {
    @SerialName("white") White(1.0, 1.0, 1.0),
    @SerialName("black") Black(0.03, 0.03, 0.04),
    @SerialName("yellow") Yellow(1.0, 0.85, 0.16),
    @SerialName("coral") Coral(1.0, 0.3, 0.36),
    @SerialName("mint") Mint(0.35, 0.94, 0.72),
    @SerialName("blue") Blue(0.35, 0.66, 1.0),
}

/**
 * The preset caption fonts of older projects. The names are the original
 * platform's font names: a renderer elsewhere must map them to faces it has.
 */
@Serializable
enum class SubtitleFont(val title: String) {
    @SerialName("system") System("System"),
    @SerialName("modern") Modern("Modern"),
    @SerialName("classic") Classic("Classic"),
    @SerialName("mono") Mono("Mono"),
    @SerialName("condensed") Condensed("Narrow");

    fun postScriptName(bold: Boolean): String? = when (this) {
        System -> null
        Modern -> if (bold) "AvenirNext-DemiBold" else "AvenirNext-Regular"
        Classic -> if (bold) "Georgia-Bold" else "Georgia"
        Mono -> if (bold) "Menlo-Bold" else "Menlo-Regular"
        Condensed -> "DINCondensed-Bold"
    }
}

/**
 * Packed sRGB, in RRGGBBAA order. Stored values are independent of the display's color scheme.
 * A Long holds the unsigned 32-bit value the original saves.
 */
@Serializable
data class TitleInk(val rgba: Long) {
    constructor(rgb: Long, alpha: Int) : this(((rgb shl 8) or (alpha.toLong() and 0xff)) and 0xffffffffL)

    val rgb: Long get() = rgba shr 8
    val red: Double get() = ((rgba shr 24) and 0xff) / 255.0
    val green: Double get() = ((rgba shr 16) and 0xff) / 255.0
    val blue: Double get() = ((rgba shr 8) and 0xff) / 255.0
    val alpha: Double get() = (rgba and 0xff) / 255.0
    val valid: Boolean get() = rgba in 0..0xffffffffL

    companion object {
        val WHITE = opaque(0xffffff)
        val BLACK = opaque(0x000000)

        fun opaque(rgb: Long): TitleInk = TitleInk(rgb, 255)

        fun of(color: TextColor): TitleInk = opaque(
            (roundedAwayFromZero(color.red * 255).toLong() shl 16) or (roundedAwayFromZero(color.green * 255).toLong() shl 8) or
                roundedAwayFromZero(color.blue * 255).toLong(),
        )
    }
}

@Serializable
data class TitleGradient(
    val first: TitleInk,
    val last: TitleInk,
    /** Screen degrees: 0 left to right, 90 top to bottom. */
    val angle: Double,
) {
    constructor(first: Long, last: Long, angle: Double) : this(TitleInk.opaque(first), TitleInk.opaque(last), angle)

    /** The two points, in a box of [size], between which the colors run so the gradient just covers the box. */
    fun axis(size: Size): Pair<Point, Point> {
        val a = angle * Math.PI / 180
        val x = cos(a)
        val y = sin(a)
        val half = (abs(x) * size.width + abs(y) * size.height) / 2
        return Point(size.width / 2 - x * half, size.height / 2 - y * half) to Point(size.width / 2 + x * half, size.height / 2 + y * half)
    }

    companion object {
        val gallery: List<Pair<String, TitleGradient>> = listOf(
            "Sunset" to TitleGradient(0xfff0ba, 0xf58283, 105.0), "Golden hour" to TitleGradient(0xfff5d6, 0xe4a044, 85.0),
            "Ember" to TitleGradient(0xffcf74, 0xc84939, 30.0), "Rose" to TitleGradient(0xffdae2, 0xba5486, 120.0),
            "Lavender" to TitleGradient(0xeee0ff, 0x9a87dd, 95.0), "Midnight" to TitleGradient(0x91aff1, 0x45417b, 45.0),
            "Glacier" to TitleGradient(0xf6ffff, 0x50afcd, 100.0), "Lagoon" to TitleGradient(0x72e7df, 0x386ab1, 20.0),
            "Sea glass" to TitleGradient(0xe4fadb, 0x69b7a7, 130.0), "Olive" to TitleGradient(0xf4e8aa, 0x829969, 70.0),
            "Silver" to TitleGradient(0xffffff, 0x9ca9b8, 90.0), "Copper" to TitleGradient(0xf0c6a5, 0xa36556, 145.0),
        )
    }
}

@Serializable
enum class TitleAlignment {
    @SerialName("left") Left,
    @SerialName("center") Center,
    @SerialName("right") Right,
}

/** What sits behind the words: nothing, one block behind all of them, or a strip behind each line. */
@Serializable
enum class TitlePlate {
    @SerialName("off") Off,
    @SerialName("block") Block,
    @SerialName("lines") Lines,
}

/**
 * How a title looks. Lengths are in em, the font size, so a design scales
 * with its title. [face] is a reference, never a loaded font: "system", the
 * id of a bundled [TitleTypeface], an "import:" reference to a font file kept
 * with the project, or a platform font name from an older project.
 */
@Serializable
data class TitleDesign(
    val face: String = "system",
    val weight: Int = 700,
    val italic: Boolean = false,
    val alignment: TitleAlignment = TitleAlignment.Center,
    val tracking: Double = 0.0,
    val lineSpacing: Double = 0.0,
    val fill: TitleInk = TitleInk.WHITE,
    /** Replaces [fill] when set. */
    val gradient: TitleGradient? = null,
    val outlined: Boolean = false,
    val border: TitleInk = TitleInk.BLACK,
    /** Thickness in em, outside the glyph. */
    val borderWidth: Double = 0.045,
    val shadow: Boolean = true,
    val shade: TitleInk = TitleInk(0x000000, alpha = 210),
    val shadowBlur: Double = 0.12,
    val shadowOffset: Double = 0.05,
    val plate: TitlePlate = TitlePlate.Off,
    val backing: TitleInk = TitleInk(0x111218, alpha = 205),
    val padding: Double = 0.28,
    val radius: Double = 0.14,
) {
    /** The design with every color neutralized: two designs with the same key lay out identically. */
    val layoutKey: TitleDesign
        get() = copy(fill = TitleInk.WHITE, gradient = null, border = TitleInk.BLACK, shade = TitleInk.BLACK, backing = TitleInk.BLACK)

    /** Outline is a separate tool, including when switching between saved channel looks. */
    fun applyingLook(look: TitleDesign): TitleDesign = look.copy(outlined = outlined, border = border, borderWidth = borderWidth)

    /** The ranges a saved project may hold. */
    val valid: Boolean
        get() = face.isNotEmpty() && face.toByteArray().size <= 255 && weight in 100..1000 && tracking in -0.05..0.25 &&
            lineSpacing in 0.0..0.8 && borderWidth in 0.0..0.20 && shadowBlur in 0.0..0.6 && shadowOffset in -0.5..0.5 &&
            padding in 0.0..0.8 && radius in 0.0..0.8 && (gradient?.angle ?: 0.0) in 0.0..360.0 &&
            listOfNotNull(fill, border, shade, backing, gradient?.first, gradient?.last).all { it.valid }

    companion object {
        /**
         * The look of a title saved without a design, as this app has always
         * drawn it: bold white system letters with a dark outline and no shadow.
         */
        val plain = TitleDesign(outlined = true, borderWidth = 0.06, shadow = false)

        /** What an older title, saved as a style and a color, looks like as a design. */
        fun legacy(style: TextStyle, color: TextColor, font: SubtitleFont? = null): TitleDesign {
            val base = TitleDesign(
                face = font?.postScriptName(bold = style != TextStyle.Clean) ?: "system",
                weight = if (style == TextStyle.Clean) 400 else 700,
                fill = if (style == TextStyle.Highlight) TitleInk.BLACK else TitleInk.of(color),
            )
            if (style != TextStyle.Box && style != TextStyle.Highlight) return base
            return base.copy(
                shadow = false,
                plate = TitlePlate.Block,
                backing = if (style == TextStyle.Highlight) TitleInk.of(color) else TitleInk(0x050505, alpha = 209),
            )
        }

        /** The built-in looks offered when styling a title. */
        val looks: List<Pair<String, TitleDesign>> = run {
            val label = TitleDesign(face = "ibmplexmono", weight = 500, tracking = 0.06)
            listOf(
                "Clean" to TitleDesign(face = "atkinsonhyperlegiblenext", weight = 600),
                "Film title" to TitleDesign(face = "sourceserif4", weight = 500),
                "Lower third" to TitleDesign(face = "barlowcondensed", plate = TitlePlate.Block, shadow = false, alignment = TitleAlignment.Left),
                "Headline" to TitleDesign(face = "archivoblack"),
                "Field note" to TitleDesign(face = "caveat", weight = 600),
                "Dateline" to label,
                "Warm editorial" to TitleDesign(face = "fraunces", gradient = TitleGradient.gallery[0].second),
                "Sunset mono" to label.copy(gradient = TitleGradient.gallery[0].second),
            )
        }
    }
}

/**
 * A bundled font family, as names only. [upright] and [slanted] are the names
 * of the font resources; loading them belongs to the renderer.
 */
data class TitleTypeface(
    val id: String,
    val name: String,
    val upright: String,
    val slanted: String? = null,
    val weights: List<Int> = listOf(400, 500, 600, 700, 800),
) {
    companion object {
        val families: List<TitleTypeface> = listOf(
            TitleTypeface("system", "System", "", ""),
            TitleTypeface("atkinsonhyperlegiblenext", "Atkinson Hyperlegible Next", "AtkinsonHyperlegibleNext-Regular", "AtkinsonHyperlegibleNext-Italic"),
            TitleTypeface("barlowcondensed", "Barlow Condensed", "BarlowCondensed-Regular", "BarlowCondensed-Italic", listOf(400, 700)),
            TitleTypeface("archivoblack", "Archivo Black", "ArchivoBlack-Regular", weights = listOf(400)),
            TitleTypeface("sourceserif4", "Source Serif 4", "SourceSerif4Roman-Regular", "SourceSerif4Italic-Italic"),
            TitleTypeface("fraunces", "Fraunces", "Fraunces-9ptBlack", "Fraunces-9ptBlackItalic"),
            TitleTypeface("ibmplexmono", "IBM Plex Mono", "IBMPlexMono-Regular", "IBMPlexMono-Italic", listOf(400, 500, 700)),
            TitleTypeface("caveat", "Caveat", "Caveat-Regular", weights = listOf(400, 500, 600, 700)),
        )

        fun family(id: String): TitleTypeface? = families.firstOrNull { it.id == id }

        /**
         * The font resource a design asks for. Null for the system font and for
         * an imported one, which are not bundled; an unknown [TitleDesign.face]
         * is returned as it is, a font name from an older project.
         *
         * Two families ship as one file per weight rather than one variable
         * file, so their weight is part of the name; the others take
         * [TitleDesign.weight] on their weight axis.
         */
        fun resourceName(design: TitleDesign): String? {
            if (design.face.startsWith("import:")) return null
            val family = family(design.face) ?: return design.face
            if (family.id == "system") return null
            if (family.id == "barlowcondensed" || family.id == "ibmplexmono") {
                val suffix = if (design.weight >= 600) "Bold" else if (family.id == "ibmplexmono" && design.weight >= 500) "Medium" else ""
                return family.upright.substringBefore("-") + "-" + suffix + if (design.italic) "Italic" else if (suffix.isEmpty()) "Regular" else ""
            }
            return if (design.italic) family.slanted ?: family.upright else family.upright
        }
    }
}

/** An "import:digest:index:name" face: one font of a file kept with the project, named by its content. */
data class ImportedFontReference(val digest: String, val index: Int, val postScriptName: String) {
    val filename: String get() = "font-$digest.sfnt"

    companion object {
        fun parse(id: String): ImportedFontReference? {
            if (!id.startsWith("import:")) return null
            // The name is last and keeps any colons of its own.
            val parts = id.split(":", limit = 4)
            if (parts.size != 4 || parts[3].isEmpty()) return null
            val digest = parts[1]
            if (digest.length != 64 || digest.any { it !in "0123456789abcdef" }) return null
            val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val name = parts[3]
            return ImportedFontReference(digest, index, name)
        }
    }
}
