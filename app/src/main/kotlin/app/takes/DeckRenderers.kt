@file:OptIn(UnstableApi::class)

package app.takes

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
 * ExoPlayer's renderers for a deck of the editor's preview, with the video decoder asked for
 * not in real time: `KEY_PRIORITY` 1 and no operating rate, as the export asks for its own.
 * ExoPlayer asks for priority 0 at the clip's frame rate, and a phone may hold only one such
 * 4K decoder: on the Galaxy A54 the second deck's fails with `NO_MEMORY`, so the preview stops
 * at a transition between two 4K clips (docs/LAYERS-PROBE.md, section 4.5).
 */
internal class DeckRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out += object : MediaCodecVideoRenderer(builder) {
            // Unset also when the speed changes, so the decoder is never given a rate to keep.
            override fun getCodecOperatingRateV23(targetPlaybackSpeed: Float, format: Format, streamFormats: Array<out Format>) =
                CODEC_OPERATING_RATE_UNSET

            override fun getMediaFormat(
                format: Format, codecMimeType: String, codecMaxValues: CodecMaxValues, codecOperatingRate: Float,
                deviceNeedsNoPostProcessWorkaround: Boolean, tunnelingAudioSessionId: Int,
            ): MediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
                .apply { setInteger(MediaFormat.KEY_PRIORITY, 1) }
        }
    }
}
