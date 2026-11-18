package app.takes

import android.content.Context
import android.util.Log
import app.takes.core.AudioEffect
import app.takes.core.AudioEffectKind
import app.takes.restoration.LavaNetwork
import app.takes.restoration.RestorationModels
import app.takes.restoration.VoiceFixerNetwork
import app.takes.speech.SpeechModel
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Where the models of Enhance audio's speech restoration stages live on the phone, LavaSR v2 for speech detail
 * and VoiceFixer for studio restoration ([RestorationModels]). The original carries them; here each is
 * downloaded when the person adds its stage ([downloadModel], checked file by file against its SHA-256),
 * kept outside backups until removed, and loaded when a chain needs it.
 */
object RestorationModelStore {
    @Volatile private var root: File? = null

    /** Called once when the app starts. */
    fun init(context: Context) {
        root = File(context.noBackupFilesDir, "audio")
    }

    fun folder(model: SpeechModel): File = File(checkNotNull(root) { "RestorationModelStore.init was not called" }, model.folder)

    fun installed(model: SpeechModel): Boolean = root != null && model.installed(folder(model))

    /** The bytes of [model] on the phone: all of it, the part an interrupted download left, or nothing. */
    fun present(model: SpeechModel): Long = if (root == null) 0 else model.present(folder(model))

    /** Whether this phone plays [effect], a speech restoration stage: its model is the original's and is downloaded. */
    fun plays(effect: AudioEffect): Boolean {
        val model = RestorationModels.of(effect.model) ?: return false
        return RestorationModels.of(effect.kind) == model && installed(model)
    }

    /** The model a stage of [kind] needs and this phone lacks, or null. */
    fun missing(kind: AudioEffectKind): SpeechModel? = RestorationModels.of(kind)?.takeUnless { installed(it) }

    /** Bytes free where the model goes, less what an interrupted download already holds there. */
    fun hasRoom(model: SpeechModel): Boolean {
        val folder = folder(model)
        val free = generateSequence(folder) { it.parentFile }.first { it.exists() }.usableSpace
        return free <= 0 || free > model.bytes - model.present(folder) + SubtitleModel.RESERVE
    }

    suspend fun download(model: SpeechModel, progress: (Long) -> Unit) = downloadModel(model, folder(model), progress)

    /** Deletes [model] and any unfinished download of it. Sound already processed with it is kept. */
    fun remove(model: SpeechModel) {
        synchronized(this) {
            if (model == RestorationModels.Lava) { lava?.close(); lava = null }
            if (model == RestorationModels.VoiceFixer) { voiceFixer?.close(); voiceFixer = null }
        }
        model.remove(folder(model))
    }

    // The networks, loaded on first use and kept while chains are being made; closed a while after the last,
    // since VoiceFixer's hold over 400 MB.
    private var lava: LavaNetwork? = null
    private var voiceFixer: VoiceFixerNetwork? = null
    private var users = 0
    private var closing: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "restoration-models").apply { isDaemon = true } }

    /** LavaSR's network, loaded if needed. Between [use] and [done]. */
    @Synchronized fun lava(): LavaNetwork = lava ?: LavaNetwork.open(folder(RestorationModels.Lava)).also { lava = it }

    /** VoiceFixer's networks, loaded if needed. Between [use] and [done]. */
    @Synchronized fun voiceFixer(): VoiceFixerNetwork = voiceFixer ?: VoiceFixerNetwork.open(folder(RestorationModels.VoiceFixer)).also { voiceFixer = it }

    /** A chain is about to be made: the networks stay loaded until [done]. */
    @Synchronized fun use() {
        users++
        closing?.cancel(false)
        closing = null
    }

    /** A chain is made: the networks go a minute after the last one, unless another one comes. */
    @Synchronized fun done() {
        users = maxOf(0, users - 1)
        if (users > 0 || (lava == null && voiceFixer == null)) return
        closing = timer.schedule({
            synchronized(this) {
                if (users > 0) return@synchronized
                Log.i("Takes", "Speech restoration models released")
                lava?.close(); lava = null
                voiceFixer?.close(); voiceFixer = null
            }
        }, IDLE_SECONDS, TimeUnit.SECONDS)
    }

    private const val IDLE_SECONDS = 60L
}
