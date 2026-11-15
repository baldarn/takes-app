package app.takes.speech

/**
 * Which models there are to choose from, and which one is used when nobody chose.
 *
 * Plain arithmetic on what the caller knows about the phone (its memory, which models are
 * on it, what the person picked), so that it can be tested without a phone.
 */
object SpeechModels {
    /** What can be downloaded, the most accurate first. */
    val offered: List<SpeechModel> = listOf(SpeechModel.Qwen3Asr, SpeechModel.WhisperSmall)

    /** Every model a phone can hold: the offered ones, and the one earlier versions downloaded. */
    val known: List<SpeechModel> = offered + SpeechModel.WhisperBase

    /**
     * The memory a phone must have, as Android reports it in total, for Qwen3-ASR to be the
     * model it gets without asking. Android reports less than the phone is sold with ("8 GB"
     * is about 7.6 GB, "6 GB" about 5.6 GB), so this admits phones sold with 8 GB or more.
     *
     * Measured on a phone sold with 8 GB: Qwen3-ASR took 2.1 GB beyond the app's own 0.2 GB,
     * and the memory the phone had left went from 3.6 GB to 1.8 GB. A phone with 2 GB less
     * would have had less to give than the model takes. The figures are in the README.
     */
    const val QWEN_MINIMUM_RAM_BYTES = 7_000_000_000L

    /** The model a phone with [totalRam] bytes of memory gets when nobody chose. */
    fun recommended(totalRam: Long): SpeechModel = if (totalRam >= QWEN_MINIMUM_RAM_BYTES) SpeechModel.Qwen3Asr else SpeechModel.WhisperSmall

    /**
     * The model in use. [chosen] is the id the person picked, or null when they never did;
     * [installed] are the ids of the models on the phone.
     *
     * A choice holds for as long as it can be acted on: an offered model can always be
     * downloaded, the earlier one only while it is still on the phone. Without a choice, the
     * phone's recommended model is used if it is there, else whatever is there already (so
     * a phone that has the earlier model keeps using it), else the recommended one, to download.
     */
    fun selected(chosen: String?, installed: Set<String>, totalRam: Long): SpeechModel {
        known.firstOrNull { it.id == chosen && (it in offered || it.id in installed) }?.let { return it }
        val recommended = recommended(totalRam)
        return if (recommended.id in installed) recommended else known.firstOrNull { it.id in installed } ?: recommended
    }

    /** What the choice shows: the offered models, and the earlier one while it is on the phone or in use. */
    fun choices(installed: Set<String>, selected: SpeechModel): List<SpeechModel> =
        known.filter { it in offered || it.id in installed || it == selected }

    /** The offered model to fall back to when [model] is too much for the phone: the one that takes the most memory among those that take less. */
    fun lighter(model: SpeechModel): SpeechModel? = offered.filter { it.memory < model.memory }.maxByOrNull { it.memory }

    /** Whether [free] bytes of memory, what the phone can give at this moment, are enough to recognize with [model]. */
    fun fits(model: SpeechModel, free: Long): Boolean = free >= model.memory
}
