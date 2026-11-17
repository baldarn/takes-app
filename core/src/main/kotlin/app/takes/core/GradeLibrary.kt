package app.takes.core

import kotlinx.serialization.Serializable

/**
 * The person's colour library, kept for the whole app rather than a project,
 * as the original's (YouRec/ColorGrading.swift:137-157): the LUTs imported,
 * and the looks and LUTs marked as favourites, by the id the Color tool
 * selects them by ([ColorLook] name or [ImportedLUT.filename]). The LUT files
 * themselves lie beside it and are copied into a project when one is used,
 * so a project never depends on the library.
 */
@Serializable
data class GradeLibrary(
    val imported: List<ImportedLUT> = emptyList(),
    val favorites: Set<String> = emptySet(),
) {
    /** The library's tabs in the Color tool (YouRec/ColorGradeControls.swift:76-78). */
    enum class Category { BuiltIn, Imported, Favorites }

    /** With [lut] among the imported ones, once (YouRec/EditorTools.swift:560). */
    fun importing(lut: ImportedLUT): GradeLibrary =
        if (imported.any { it.filename == lut.filename }) this else copy(imported = imported + lut)

    /** With [id] a favourite, or no longer one (EditorTools.swift:572-575). */
    fun toggled(id: String): GradeLibrary = copy(favorites = if (id in favorites) favorites - id else favorites + id)

    /** With the imported LUT [lut] stands for given what [lut] says it expects (ColorGradeControls.swift:103-107). */
    fun expecting(lut: ImportedLUT): GradeLibrary = copy(imported = imported.map { if (it.filename == lut.filename) it.copy(input = lut.input) else it })

    /** The built-in looks [category] shows, in the original's order (ColorGradeControls.swift:86-90). */
    fun looks(category: Category): List<ColorLook> = when (category) {
        Category.BuiltIn -> ColorLook.entries
        Category.Favorites -> ColorLook.entries.filter { id(it) in favorites }
        Category.Imported -> emptyList()
    }

    /** The imported LUTs [category] shows, in the order they came (ColorGradeControls.swift:91-95). */
    fun luts(category: Category): List<ImportedLUT> = when (category) {
        Category.Imported -> imported
        Category.Favorites -> imported.filter { it.filename in favorites }
        Category.BuiltIn -> emptyList()
    }

    /** The library as its file holds it. */
    fun encoded(): String = documentJson.encodeToString(serializer(), this)

    companion object {
        /** The id a look is selected and marked by: its raw value in the original. */
        fun id(look: ColorLook): String = ColorLook.serializer().descriptor.getElementName(look.ordinal)

        /** The id a grade is selected by: its LUT's when it has one, else its look's (ColorGradeControls.swift:11). */
        fun id(settings: ColorSettings): String = settings.imported?.filename ?: id(settings.look)

        /**
         * The library read from its file; an empty one when there is none or it cannot be read, as the original
         * (ColorGrading.swift:144-146). LUTs that are not valid references are left out.
         */
        fun decoded(text: String?): GradeLibrary {
            val read = text?.let { runCatching { documentJson.decodeFromString(serializer(), it) }.getOrNull() } ?: return GradeLibrary()
            return read.copy(imported = read.imported.filter { it.valid })
        }
    }
}
