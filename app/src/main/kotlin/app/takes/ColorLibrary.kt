package app.takes

import android.content.Context
import app.takes.core.GradeLibrary
import app.takes.core.ImportedLUT
import app.takes.core.ProjectDisk
import java.io.File

/**
 * Where the colour library is kept: `library.json` and the LUT files beside it, in a folder of the app's own,
 * as the original keeps its library in Application Support/ColorLibrary (YouRec/ColorGrading.swift:141-156).
 * A LUT used in a project is copied into the project's folder, so the project and its archive keep working
 * without the library.
 */
internal class ColorLibrary(context: Context) {
    val folder = File(context.filesDir, "ColorLibrary")
    private val file = File(folder, "library.json")

    fun read(): GradeLibrary = GradeLibrary.decoded(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    /** Writes [library]; false when it could not be written. */
    fun save(library: GradeLibrary): Boolean = runCatching {
        folder.mkdirs()
        ProjectDisk.writeAtomically(library.encoded().toByteArray(), file)
    }.isSuccess

    /** Copies [lut]'s file from the folder [from] to [to], unless it is there already; false when it could not be. */
    fun copy(lut: ImportedLUT, from: File, to: File): Boolean = runCatching {
        require(lut.valid)
        val target = File(to, lut.filename)
        if (!target.isFile) {
            to.mkdirs()
            ProjectDisk.writeAtomically(File(from, lut.filename).readBytes(), target)
        }
    }.isSuccess
}
