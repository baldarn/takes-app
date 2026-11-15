@file:OptIn(UnstableApi::class)

package app.takes.probe.layers

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * ExoPlayer's renderers, with the video decoder's configuration told to [log]; with [realtime]
 * false the decoder is asked for as Transformer asks for it, not in real time: no
 * `KEY_PRIORITY` 0 and no operating rate.
 */
class ProbeRenderers(context: Context, private val realtime: Boolean, private val log: (String) -> Unit) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        out += object : MediaCodecVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
            override fun getCodecOperatingRateV23(targetPlaybackSpeed: Float, format: Format, streamFormats: Array<out Format>): Float =
                if (realtime) super.getCodecOperatingRateV23(targetPlaybackSpeed, format, streamFormats) else CODEC_OPERATING_RATE_UNSET

            override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxValues: CodecMaxValues, codecOperatingRate: Float, deviceNeedsNoPostProcessWorkaround: Boolean, tunnelingAudioSessionId: Int): MediaFormat {
                val media = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
                if (!realtime) media.setInteger(MediaFormat.KEY_PRIORITY, 1)
                log("decoder configured: " + listOf(MediaFormat.KEY_PRIORITY, MediaFormat.KEY_OPERATING_RATE, MediaFormat.KEY_MAX_WIDTH, MediaFormat.KEY_MAX_HEIGHT, MediaFormat.KEY_MAX_INPUT_SIZE)
                    .joinToString { key -> "$key=" + if (media.containsKey(key)) (runCatching { media.getInteger(key).toString() }.getOrNull() ?: media.getFloat(key).toString()) else "-" })
                return media
            }
        }
    }
}
