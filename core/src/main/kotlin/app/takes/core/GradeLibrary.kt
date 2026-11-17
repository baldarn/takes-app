package app.takes.core

import kotlinx.serialization.Serializable

/**
 * The person's colour library, kept for the whole app rather than a project,
 * as the original's (YouRec/ColorGrading.swift:131-157): the LUTs imported,
 * the grades saved under a name, and the looks and LUTs marked as favourites, by the id the Color tool
 * selects them by ([ColorLook] name or [ImportedLUT.filename]). The LUT files
 * themselves lie beside it and are copied into a project when one is used,
 * so a project never depends on the library.
 */
@Serializable
data class GradeLibrary(
    val imported: List<ImportedLUT> = emptyList(),
    val grades: List<SavedGrade> = emptyList(),
    val favorites: Set<String> = emptySet(),
) {
    /** The library's tabs in the Color tool (YouRec/ColorGradeControls.swift:76-78). */
    enum class Category { BuiltIn, Imported, Favorites, Grades }

    /**
     * With [settings] saved as a grade under [name], trimmed and at most 80 characters; null when the name is
     * empty, and nothing is saved (YouRec/EditorTools.swift:565-570).
     */
    fun saving(name: String, settings: ColorSettings): GradeLibrary? {
        val clean = name.trim().take(MAXIMUM_NAME).ifEmpty { return null }
        return copy(grades = grades + SavedGrade(name = clean, settings = settings))
    }

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
        Category.Imported, Category.Grades -> emptyList()
    }

    /** The imported LUTs [category] shows, in the order they came (ColorGradeControls.swift:91-95). */
    fun luts(category: Category): List<ImportedLUT> = when (category) {
        Category.Imported -> imported
        Category.Favorites -> imported.filter { it.filename in favorites }
        Category.BuiltIn, Category.Grades -> emptyList()
    }

    /** The library as its file holds it. */
    fun encoded(): String = documentJson.encodeToString(serializer(), this)

    companion object {
        /** The longest name a grade is saved under (EditorTools.swift:566). */
        const val MAXIMUM_NAME = 80

        /** The id a look is selected and marked by: its raw value in the original. */
        fun id(look: ColorLook): String = ColorLook.serializer().descriptor.getElementName(look.ordinal)

        /** The id a grade is selected by: its LUT's when it has one, else its look's (ColorGradeControls.swift:11). */
        fun id(settings: ColorSettings): String = settings.imported?.filename ?: id(settings.look)

        /**
         * The library read from its file; an empty one when there is none or it cannot be read, as the original
         * (ColorGrading.swift:144-146). LUTs that are not valid references, and grades that are not valid, are left out.
         */
        fun decoded(text: String?): GradeLibrary {
            val read = text?.let { runCatching { documentJson.decodeFromString(serializer(), it) }.getOrNull() } ?: return GradeLibrary()
            return read.copy(imported = read.imported.filter { it.valid }, grades = read.grades.filter { it.name.isNotBlank() && it.settings.valid })
        }
    }
}

/** A grade saved under a name, for other clips and projects (YouRec/ColorGrading.swift:131-135). */
@Serializable
data class SavedGrade(val id: String = newID(), val name: String, val settings: ColorSettings) {
    /**
     * [current] given this grade's look, LUT, strength, Highlights, recovery, exposure and white balance, as the
     * original selects a grade (YouRec/EditorTools.swift:544-553): it keeps its own id and its motion.
     */
    fun applied(current: ColorSettings): ColorSettings = current.copy(
        look = settings.look, amount = settings.amount, imported = settings.imported, exposure = settings.exposure,
        highlights = settings.highlights, localRecovery = settings.localRecovery, warmth = settings.warmth, tint = settings.tint,
    )
}
