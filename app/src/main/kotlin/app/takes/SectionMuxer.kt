@file:OptIn(UnstableApi::class)

package app.takes

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.transformer.DefaultMuxer
import app.takes.core.ExportSections
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer

/**
 * The muxer of one run of the encoder over several sections ([ExportSections.Run]): it writes the encoded picture
 * into one file a section, each timed from zero as a section rendered alone is, and hands every file over as soon
 * as its last frame is written ([finished]), so that a pause keeps the sections done so far.
 *
 * Starting the decoders, the compositor and the encoder again for every section, and drawing half a second either
 * side of it, cost a Galaxy A54 about half a second a section; one run costs it once. A section must begin with a
 * key frame. The encoder is asked for one every second at the movie's frame rate, and a section is a whole number
 * of seconds, so its first frame is one; should an encoder place them otherwise, the run stops where a section
 * would not begin on one ([stoppedAt]) and the next run starts there with an encoder of its own.
 *
 * [run] is what the run renders, [skip] says which of its sections are saved already and are not written again,
 * [file] gives a new file to write a section into, and [starting] is told of each section before it is begun (and
 * may refuse it, for want of space).
 */
class SectionMuxer(
    private val run: ExportSections.Run,
    private val skip: (ExportSections.Section) -> Boolean,
    private val file: () -> File,
    private val starting: (ExportSections.Section) -> Unit,
    private val finished: (ExportSections.Section, File) -> Unit,
) : Muxer.Factory {
    private val sections = DefaultMuxer.Factory()

    /** The position in the run of the section at which it stopped for want of a key frame, if it did. */
    @Volatile var stoppedAt: Int? = null
        private set

    override fun create(path: String): Muxer = Split()

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = sections.getSupportedSampleMimeTypes(trackType)

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = sections.supportsWritingNegativeTimestampsInEditList()

    private inner class Split : Muxer {
        private var format: Format? = null
        private val metadata = mutableListOf<Metadata.Entry>()
        private var current: Muxer? = null
        private var currentFile: File? = null
        private var position = -1
        private var track = 0

        override fun addTrack(format: Format): Int {
            if (this.format != null) throw MuxerException("A run of sections holds one track", IllegalStateException())
            this.format = format
            return 0
        }

        override fun addMetadataEntry(entry: Metadata.Entry) {
            metadata += entry
            current?.addMetadataEntry(entry)
        }

        override fun writeSampleData(trackId: Int, buffer: ByteBuffer, info: BufferInfo) {
            if (info.size == 0) return
            val at = run.position(run.frame(info.presentationTimeUs)) ?: return
            if (at < position) stop(at) // a frame of a section already closed: the encoder does not give them in order
            if (at > position) {
                finishSection()
                position = at
                if (!skip(run.sections[at])) {
                    if (info.flags and androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME == 0) stop(at)
                    open()
                }
            }
            val writing = current ?: return
            writing.writeSampleData(track, buffer, BufferInfo(maxOf(0L, info.presentationTimeUs - run.beginsMicros(position)), info.size, info.flags))
        }

        private fun open() {
            try {
                starting(run.sections[position])
            } catch (refused: Exception) {
                throw MuxerException("Section ${run.sections[position].index} refused", refused)
            }
            val made = file()
            val muxer = sections.create(made.path)
            track = muxer.addTrack(format ?: throw MuxerException("No track", IllegalStateException()))
            metadata.forEach(muxer::addMetadataEntry)
            current = muxer
            currentFile = made
        }

        private fun stop(at: Int): Nothing {
            stoppedAt = at
            current?.let { runCatching { it.close() } }
            currentFile?.delete()
            current = null
            currentFile = null
            throw MuxerException("Section ${run.sections[at].index} does not begin on a key frame", IllegalStateException())
        }

        /** Closes the section being written and hands it over. */
        private fun finishSection() {
            val muxer = current ?: return
            val made = currentFile!!
            current = null
            currentFile = null
            muxer.close()
            finished(run.sections[position], made)
        }

        override fun close() {
            // A cancelled run closes too: the section it was writing is handed over unfinished and is found so
            // by its count of frames.
            finishSection()
        }
    }
}
