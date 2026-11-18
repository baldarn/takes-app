package app.takes.core

import kotlinx.serialization.Serializable

/**
 * A title of two texts on a card: a small label above a headline
 * (YouRec/TitleDesign.swift:187-205). The headline's words are the title's
 * own [TextOverlay.text]; both texts share the title's anchor, size, timing
 * and motion. Sizes and the card's lengths are in units of the title's size.
 */
@Serializable
data class LabelHeadline(
    val label: String = "First text",
    val labelStyle: TitleDesign = TitleDesign(
        face = "system", weight = 700, alignment = TitleAlignment.Left, tracking = 0.09,
        fill = TitleInk.BLACK, shadow = false, plate = TitlePlate.Block, backing = TitleInk.WHITE, padding = 0.32, radius = 0.27,
    ),
    val headlineStyle: TitleDesign = TitleDesign(face = "archivoblack", weight = 400, alignment = TitleAlignment.Left, tracking = -0.045, shadow = false),
    val labelScale: Double = 0.36,
    val headlineScale: Double = 1.0,
    val card: TitleCard = TitleCard(),
) {
    /** What a tap on the title or the part picker edits (YouRec/TitleDesign.swift:189). [title] is the original's English name. */
    enum class Part(val title: String) { Label("Label"), Headline("Headline"), Card("Card") }

    /** The ranges a saved project may hold; the label is counted in UTF-16 units, as a title's text is here. */
    val valid: Boolean
        get() = label.length <= 500 && labelStyle.valid && headlineStyle.valid && card.valid &&
            labelScale in 0.2..1.0 && headlineScale in 0.5..1.8

    /** The appearance alone, as a look keeps it: no words and no placement of the texts (YouRec/TitleDesign.swift:235-244). */
    val look: LabelHeadlineLook
        get() = LabelHeadlineLook(
            TitleDesign().applyingLook(labelStyle), TitleDesign().applyingLook(headlineStyle), labelScale, headlineScale,
            card.copy(labelOffset = null, headlineOffset = null),
        )

    /**
     * The card in [look], keeping its words, the places its texts were moved
     * to and each text's border, which is a tool of its own (YouRec/TitleDesign.swift:246-256).
     */
    fun applyingLook(look: LabelHeadlineLook): LabelHeadline = copy(
        labelStyle = labelStyle.applyingLook(look.label), headlineStyle = headlineStyle.applyingLook(look.headline),
        labelScale = look.labelScale, headlineScale = look.headlineScale,
        card = look.card.copy(labelOffset = card.labelOffset, headlineOffset = card.headlineOffset),
    )

    companion object {
        /** The headline a title gets when it becomes a card with nothing written (YouRec/TitleDesign.swift:191). */
        const val DEFAULT_HEADLINE = "Second text"

        /** The six built-in looks of a card, by their English names (YouRec/TitleDesign.swift:258-290). */
        val looks: List<Pair<String, LabelHeadlineLook>> = run {
            val broadcast = LabelHeadlineLook(
                label = TitleDesign(face = "barlowcondensed", weight = 700, alignment = TitleAlignment.Left, tracking = 0.08,
                    fill = TitleInk.opaque(0x111111), shadow = false, plate = TitlePlate.Block, backing = TitleInk.opaque(0xFFD60A), padding = 0.20, radius = 0.06),
                headline = TitleDesign(face = "barlowcondensed", weight = 700, alignment = TitleAlignment.Left, shadow = false),
                labelScale = 0.44, headlineScale = 1.0,
                card = TitleCard(fill = TitleInk(0x0B0F14, alpha = 236), borderWidth = 0.0, padding = 0.36, radius = 0.08, spacing = 0.12),
            )
            val editorial = LabelHeadlineLook(
                label = TitleDesign(face = "ibmplexmono", weight = 500, alignment = TitleAlignment.Left, tracking = 0.075, fill = TitleInk.opaque(0x962C26), shadow = false),
                headline = TitleDesign(face = "sourceserif4", weight = 600, alignment = TitleAlignment.Left, tracking = -0.01, fill = TitleInk.opaque(0x221D1A), shadow = false),
                labelScale = 0.42, headlineScale = 1.0,
                card = TitleCard(fill = TitleInk(0xF7F3EA, alpha = 246), border = TitleInk.opaque(0x51463C), borderWidth = 0.022, padding = 0.46, radius = 0.04, spacing = 0.12),
            )
            val terminal = LabelHeadlineLook(
                label = TitleDesign(face = "ibmplexmono", weight = 500, alignment = TitleAlignment.Left, tracking = 0.06, fill = TitleInk.opaque(0x61E3A5), shadow = false),
                headline = TitleDesign(face = "ibmplexmono", weight = 700, alignment = TitleAlignment.Left, tracking = -0.025, fill = TitleInk.opaque(0xE6EDF3), shadow = false),
                labelScale = 0.41, headlineScale = 0.94,
                card = TitleCard(fill = TitleInk(0x0D1517, alpha = 240), border = TitleInk.opaque(0x61E3A5), borderWidth = 0.025, padding = 0.40, radius = 0.12, spacing = 0.12),
            )
            val poster = LabelHeadlineLook(
                label = TitleDesign(face = "barlowcondensed", weight = 700, alignment = TitleAlignment.Left, tracking = 0.07, fill = TitleInk.BLACK,
                    shadow = false, plate = TitlePlate.Block, backing = TitleInk.WHITE, padding = 0.18, radius = 0.0),
                headline = TitleDesign(face = "archivoblack", weight = 400, alignment = TitleAlignment.Left, tracking = -0.035,
                    shadow = false, plate = TitlePlate.Block, backing = TitleInk.opaque(0xB7251F), padding = 0.15, radius = 0.0),
                labelScale = 0.44, headlineScale = 1.0,
                card = TitleCard(fill = TitleInk(0, alpha = 0), borderWidth = 0.0, padding = 0.12, radius = 0.0, spacing = 0.03),
            )
            val notebook = LabelHeadlineLook(
                label = TitleDesign(face = "caveat", weight = 700, alignment = TitleAlignment.Left, fill = TitleInk.opaque(0xFFE066), shadow = false),
                headline = TitleDesign(face = "fraunces", weight = 600, italic = true, alignment = TitleAlignment.Left, tracking = -0.01, shadow = false),
                labelScale = 0.62, headlineScale = 1.0,
                card = TitleCard(fill = TitleInk(0x14213D, alpha = 232), borderWidth = 0.0, padding = 0.44, radius = 0.30, spacing = 0.06),
            )
            listOf(
                "Graphite" to LabelHeadline().look, "Editorial" to editorial, "Broadcast" to broadcast,
                "Notebook" to notebook, "Terminal" to terminal, "Poster" to poster,
            )
        }
    }
}

/**
 * The card behind a label and a headline (YouRec/TitleDesign.swift:207-228).
 * Lengths are in units of the title's size, so the whole card scales together.
 */
@Serializable
data class TitleCard(
    val fill: TitleInk = TitleInk(0x08090B, alpha = 139),
    val border: TitleInk = TitleInk(0xFFFFFF, alpha = 72),
    val borderWidth: Double = 0.017,
    val padding: Double = 0.42,
    val radius: Double = 0.29,
    val spacing: Double = 0.08,
    /** Where the label was moved to on the card; null where the layout puts it. */
    val labelOffset: Offset? = null,
    /** Where the headline was moved to on the card; null where the layout puts it. */
    val headlineOffset: Offset? = null,
) {
    /** Each axis a share, -1 to 1, of the way from where the layout puts a text to that edge of the card. */
    @Serializable
    data class Offset(val x: Double = 0.0, val y: Double = 0.0) {
        val valid: Boolean get() = x in -1.0..1.0 && y in -1.0..1.0
    }

    val valid: Boolean
        get() = borderWidth in 0.0..0.1 && padding in 0.0..1.0 && radius in 0.0..1.0 && spacing in 0.0..1.0 &&
            labelOffset?.valid != false && headlineOffset?.valid != false && listOf(fill, border).all { it.valid }
}

/** A card's appearance alone, as a built-in look or a saved style keeps it (YouRec/TitleDesign.swift:233-240). */
@Serializable
data class LabelHeadlineLook(
    val label: TitleDesign,
    val headline: TitleDesign,
    val labelScale: Double,
    val headlineScale: Double,
    val card: TitleCard,
)

/**
 * Where the two texts of a card go and how large the card is
 * (YouRec/StyledTextRenderer.swift:144-195): the label above the headline,
 * each aligned as its design says within the wider of the two, [padding]
 * around them and [spacing] between them, then each moved by its offset.
 * Sizes are in the units the texts were laid out in, rectangles from the
 * card's top left; a text with nothing to show has no rectangle.
 */
class LabelHeadlineArrangement(
    label: Size?, labelAlignment: TitleAlignment, headline: Size?, headlineAlignment: TitleAlignment,
    card: TitleCard, fontSize: Double,
) {
    val label: Rect?
    val headline: Rect?
    val size: Size

    init {
        val pad = kotlin.math.ceil(card.padding * fontSize)
        val contentWidth = maxOf(label?.width ?: 0.0, headline?.width ?: 0.0)
        val gap = if (label != null && headline != null) kotlin.math.ceil(card.spacing * fontSize) else 0.0
        val headlineTop = pad + (label?.height ?: 0.0) + gap
        size = Size(contentWidth + 2 * pad, headlineTop + (headline?.height ?: 0.0) + pad)
        fun travel(fraction: Double, origin: Double, available: Double) = origin + fraction * (if (fraction < 0) origin else maxOf(0.0, available - origin))
        fun place(bounds: Size?, alignment: TitleAlignment, y: Double, offset: TitleCard.Offset?): Rect? {
            bounds ?: return null
            val slack = contentWidth - bounds.width
            val x = pad + when (alignment) { TitleAlignment.Left -> 0.0; TitleAlignment.Right -> slack; TitleAlignment.Center -> slack / 2 }
            return Rect(travel(offset?.x ?: 0.0, x, size.width - bounds.width), travel(offset?.y ?: 0.0, y, size.height - bounds.height), bounds.width, bounds.height)
        }
        this.label = place(label, labelAlignment, pad, card.labelOffset)
        this.headline = place(headline, headlineAlignment, headlineTop, card.headlineOffset)
    }

    /** The part of the card at ([x], [y]), shares of its width and height from its top left; the headline wins where the two overlap. */
    fun part(x: Double, y: Double): LabelHeadline.Part {
        val px = x * size.width
        val py = y * size.height
        fun Rect.holds() = px >= this.x && px < this.x + width && py >= this.y && py < this.y + height
        if (headline?.holds() == true) return LabelHeadline.Part.Headline
        if (label?.holds() == true) return LabelHeadline.Part.Label
        return LabelHeadline.Part.Card
    }
}
