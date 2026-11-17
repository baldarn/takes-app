package app.takes.core

/**
 * What the recorder does with two cameras around the layout of [DualCameraLayout]: the picture they are composed
 * into, the gestures on each picture, Swap, and the pairs of cameras offered, as the original's
 * `MultiCameraCapture` (YouRec/MultiCameraCapture.swift:93-108, 171-180, 272-302) and the recorder's
 * `DualPictureGestureView` and camera menus (YouRec/RecorderView.swift:661-681, 1426-1531).
 */
object DualCameraControls {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** The layout as the recorder keeps it between launches (the original's `dualCameraLayout`, CaptureController.swift:89-96, 950). */
    fun encode(layout: DualCameraLayout): String = json.encodeToString(DualCameraLayout.serializer(), layout)

    /** What was kept, constrained; the default layout when nothing was or it cannot be read. CaptureController.swift:90-95. */
    fun decode(text: String?): DualCameraLayout =
        text?.let { runCatching { json.decodeFromString(DualCameraLayout.serializer(), it).constrained() }.getOrNull() } ?: DualCameraLayout()

    /** The composed picture: 1080 × 1920 standing, 1920 × 1080 when the interface is in landscape. MultiCameraCapture.swift:172-176. */
    fun canvas(landscape: Boolean): Size = if (landscape) Size(1920.0, 1080.0) else Size(1080.0, 1920.0)

    /** The feed shown in [slot]: Swap exchanges the feeds and keeps the geometry. MultiCameraCapture.swift:245, 285. */
    fun feed(layout: DualCameraLayout, slot: Int): Int = if (layout.swapped) 1 - slot else slot

    /** The floating picture is the second slot of the floating layout. RecorderView.swift:1462. */
    fun isInset(layout: DualCameraLayout, slot: Int): Boolean = layout.mode == DualCameraLayout.Mode.Floating && slot == 1

    /**
     * The layout after a drag of ([dx], [dy]) points on [slot] of a [canvas]-sized view, as the original's pan
     * (RecorderView.swift:1488-1516): the inset moves; any other picture pans its feed within the spare picture,
     * which the original measures as if the feed had the canvas's shape, left and right swapped on a mirrored picture.
     */
    fun dragged(layout: DualCameraLayout, slot: Int, dx: Double, dy: Double, canvas: Size, mirrored: Boolean): DualCameraLayout {
        if (canvas.width <= 0 || canvas.height <= 0) return layout
        if (isInset(layout, slot)) {
            val rect = layout.regions(canvas)[1]
            return layout.copy(insetX = (rect.midX + dx) / canvas.width, insetY = (rect.midY + dy) / canvas.height).constrained()
        }
        val first = feed(layout, slot) == 0
        var framing = if (first) layout.first else layout.second
        val region = layout.regions(canvas)[slot]
        val placed = DualCameraLayout.placement(canvas, region, framing)
        val x = dx * if (mirrored) -1 else 1
        if (placed.width - region.width > 0.1) framing = framing.copy(x = framing.x - x / (placed.width - region.width))
        if (placed.height - region.height > 0.1) framing = framing.copy(y = framing.y - dy / (placed.height - region.height))
        return (if (first) layout.copy(first = framing) else layout.copy(second = framing)).constrained()
    }

    /**
     * The layout after a pinch of [scale] on [slot]: the inset is resized along [axis]; any other picture zooms its
     * feed, 1 to 4. RecorderView.swift:1517-1527.
     */
    fun pinched(layout: DualCameraLayout, slot: Int, scale: Double, axis: DualCameraLayout.InsetResizeAxis, canvas: Size): DualCameraLayout {
        if (!scale.isFinite() || scale <= 0) return layout
        if (isInset(layout, slot)) return layout.resizingInset(scale, axis, canvas)
        return if (feed(layout, slot) == 0) layout.copy(first = layout.first.copy(zoom = layout.first.zoom * scale)).constrained()
        else layout.copy(second = layout.second.copy(zoom = layout.second.zoom * scale)).constrained()
    }

    /** A drag on a picture other than the inset pans only once the fingers' middle has travelled 8 points. RecorderView.swift:1494-1501. */
    const val PAN_START = 8.0

    /** The divider dragged by [travel] points from where it was, [start], over a side of [length] points. RecorderView.swift:1408-1414. */
    fun divided(layout: DualCameraLayout, start: Double, travel: Double, length: Double): DualCameraLayout {
        if (length <= 0) return layout
        val value = start + travel / length
        return when (layout.mode) {
            DualCameraLayout.Mode.SideBySide -> layout.copy(verticalDivider = value).constrained()
            DualCameraLayout.Mode.TopAndBottom -> layout.copy(horizontalDivider = value).constrained()
            else -> layout
        }
    }

    /**
     * The thin dark line between the halves of side by side and top and bottom, two pixels wide over the boundary,
     * top-left coordinates; none in the other layouts, whose edge is the mask's. MultiCameraCapture.swift:298-302.
     */
    fun separator(layout: DualCameraLayout, canvas: Size): Rect? {
        val first = layout.regions(canvas)[0]
        return when (layout.mode) {
            DualCameraLayout.Mode.SideBySide -> Rect(first.maxX - 1, 0.0, 2.0, canvas.height)
            DualCameraLayout.Mode.TopAndBottom -> Rect(0.0, first.maxY - 1, canvas.width, 2.0)
            else -> null
        }
    }

    /**
     * The point of a feed of [source] size (standing as recorded) drawn at [point] of the canvas, as fractions of the
     * feed from its top left; a mirrored feed is flipped about the middle of its slot. MultiCameraCapture.swift:284-296.
     */
    fun feedPoint(layout: DualCameraLayout, slot: Int, point: Point, source: Size, canvas: Size, mirrored: Boolean): Point {
        val region = layout.regions(canvas)[slot]
        val placed = DualCameraLayout.placement(source, region, if (feed(layout, slot) == 0) layout.first else layout.second)
        val x = if (mirrored) 2 * region.midX - point.x else point.x
        return Point((x - placed.minX) / placed.width, (point.y - placed.minY) / placed.height)
    }

    // MARK: The pairs of cameras

    enum class Lens { Main, Ultrawide, Tele }

    /** A camera that can be part of a pair. */
    data class Camera(val id: String, val front: Boolean, val lens: Lens) {
        /** Rear before front, main before ultrawide before tele. MultiCameraCapture.swift:97. */
        val rank: Int get() = (if (front) 10 else 0) + lens.ordinal
    }

    data class Pair(val first: Camera, val second: Camera) {
        val id: String get() = first.id + "|" + second.id
        fun contains(id: String) = first.id == id || second.id == id
    }

    /**
     * Every pair that [combinations] (sets of camera ids the phone runs at once) allow, each in rank order, those with
     * a front camera first. MultiCameraCapture.swift:93-108.
     */
    fun pairs(cameras: List<Camera>, combinations: List<Set<String>>): List<Pair> {
        val ranked = cameras.sortedBy { it.rank }
        val pairs = mutableListOf<Pair>()
        for (i in ranked.indices) for (j in i + 1 until ranked.size) {
            val a = ranked[i]
            val b = ranked[j]
            if (combinations.any { a.id in it && b.id in it }) pairs += Pair(a, b)
        }
        return pairs.sortedBy { if (it.second.front) 0 else 1 }
    }

    /** The cameras of every pair, once each, in rank order: the First menu. RecorderView.swift:661-667. */
    fun cameras(pairs: List<Pair>): List<Camera> = pairs.flatMap { listOf(it.first, it.second) }.distinctBy { it.id }.sortedBy { it.rank }

    /** The cameras [first] can be paired with: the Second menu. RecorderView.swift:669-674. */
    fun partners(pairs: List<Pair>, first: Camera?): List<Camera> {
        first ?: return emptyList()
        return cameras(pairs).filter { camera -> camera.id != first.id && pairs.any { it.contains(first.id) && it.contains(camera.id) } }
    }

    /** The cameras in the first and the second slot, as Swap shows them. RecorderView.swift:656-657. */
    fun slots(pair: Pair?, swapped: Boolean): kotlin.Pair<Camera?, Camera?> =
        if (swapped) pair?.second to pair?.first else pair?.first to pair?.second

    /**
     * [camera] chosen in the first slot or the second: the pair to use, keeping the camera in the other slot when
     * they can run together, and whether it is shown swapped; null when no pair has the camera.
     * RecorderView.swift:675-681.
     */
    fun choose(pairs: List<Pair>, current: Pair?, swapped: Boolean, camera: Camera, firstSlot: Boolean): kotlin.Pair<Pair, Boolean>? {
        val (first, second) = slots(current, swapped)
        val other = if (firstSlot) second else first
        val candidates = pairs.filter { it.contains(camera.id) }
        val next = candidates.firstOrNull { p -> other != null && p.contains(other.id) && other.id != camera.id }
            ?: candidates.firstOrNull { it.id == current?.id } ?: candidates.firstOrNull() ?: return null
        return next to if (firstSlot) next.second.id == camera.id else next.first.id == camera.id
    }
}
