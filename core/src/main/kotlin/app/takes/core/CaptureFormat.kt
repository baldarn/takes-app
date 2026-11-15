package app.takes.core

/**
 * The picture a take is recorded with, as the settings ask for it and as the
 * camera in use can give it. Every part is asked of the camera first: what it
 * cannot do is left out, and the recorder says so instead of failing.
 *
 * From the original app: the two qualities and their names
 * (YouRec/CaptureController.swift:6-11), the bit rate
 * (YouRec/CaptureSegmentWriter.swift:225), 30 frames per second
 * (CaptureController.swift:1672-1675), the defaults of the two mirror
 * switches (CaptureController.swift:131-140).
 */
data class CaptureFormat(
    /** The frame size the take is written in, among [Camera.sizes]; null when the camera lists none. */
    val size: FrameSize?,
    /** 4K is asked for and this camera records it. */
    val ultraHD: Boolean,
    /** Recorded as HLG, 10 bit. */
    val hdr: Boolean,
    val stabilization: Stabilization,
    /** The frame rate is set to exactly 30; where the camera has no such rate it chooses its own. */
    val fixedRate: Boolean,
    /** Bits per second asked of the encoder; null leaves it to the encoder. */
    val bitRate: Int?,
) {
    enum class FrameSize { UHD, FHD, HD, SD }

    /** How the picture is steadied: with the preview, so the preview shows the recorded crop, or in the recording only. */
    enum class Stabilization { Off, PreviewAndRecording, RecordingOnly }

    /** What a camera offers to the recorder. */
    data class Camera(
        /** The sizes it records in SDR, with their pixels. */
        val sizes: Map<FrameSize, Pixels> = emptyMap(),
        /** The sizes it records as HLG, 10 bit; empty where it has no HDR. */
        val hdrSizes: Set<FrameSize> = emptySet(),
        val previewStabilization: Boolean = false,
        val recordingStabilization: Boolean = false,
        /** It lists a frame rate of exactly 30. */
        val thirty: Boolean = false,
    ) {
        val ultraHD: Boolean get() = FrameSize.UHD in sizes
        val hdr: Boolean get() = hdrSizes.isNotEmpty()
        val stabilization: Boolean get() = previewStabilization || recordingStabilization
    }

    data class Pixels(val width: Int, val height: Int)

    /** What the person chose. */
    data class Wish(val ultraHD: Boolean = false, val hdr: Boolean = false, val stabilization: Boolean = false)

    companion object {
        /** Bits per second for each pixel of the frame, at 30 frames per second (CaptureSegmentWriter.swift:225). */
        const val BITS_PER_PIXEL = 6

        const val FRAMES_PER_SECOND = 30

        /** 12.4 Mbit/s at 1080p, 49.8 at 4K. */
        fun bitRate(width: Int, height: Int): Int? =
            (width.toLong() * height * BITS_PER_PIXEL).takeIf { width > 0 && height > 0 && it <= Int.MAX_VALUE }?.toInt()

        /**
         * The size a take is written in: 4K where it is asked for and the
         * camera has it, else 1080p, else the largest size below it, else the
         * smallest above. A camera that lacks 4K records 1080p and is marked.
         */
        fun size(ultraHD: Boolean, available: Set<FrameSize>): FrameSize? {
            if (ultraHD && FrameSize.UHD in available) return FrameSize.UHD
            return listOf(FrameSize.FHD, FrameSize.HD, FrameSize.SD, FrameSize.UHD).firstOrNull { it in available }
        }

        /** The format [camera] records with when the person asks for [wish]. */
        fun of(wish: Wish, camera: Camera): CaptureFormat {
            val size = size(wish.ultraHD, camera.sizes.keys)
            // HDR only in a size the camera also records as HDR: the quality comes first, as in the original.
            val hdr = wish.hdr && size != null && size in camera.hdrSizes
            val pixels = camera.sizes[size]
            return CaptureFormat(
                size = size,
                ultraHD = size == FrameSize.UHD,
                hdr = hdr,
                stabilization = when {
                    !wish.stabilization -> Stabilization.Off
                    camera.previewStabilization -> Stabilization.PreviewAndRecording
                    camera.recordingStabilization -> Stabilization.RecordingOnly
                    else -> Stabilization.Off
                },
                fixedRate = camera.thirty,
                bitRate = pixels?.let { bitRate(it.width, it.height) },
            )
        }

        /**
         * What to try when the camera refuses a format it said it had, most
         * wanted first: the format, then without HDR, then without
         * stabilisation, then with neither. Formats that are the same are
         * tried once.
         */
        fun attempts(format: CaptureFormat): List<CaptureFormat> = listOf(
            format,
            format.copy(hdr = false),
            format.copy(stabilization = Stabilization.Off),
            format.copy(hdr = false, stabilization = Stabilization.Off),
        ).distinct()
    }
}
