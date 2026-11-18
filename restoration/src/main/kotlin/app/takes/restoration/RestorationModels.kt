package app.takes.restoration

import app.takes.core.AudioEffectKind
import app.takes.core.AudioModel
import app.takes.speech.SpeechModel
import app.takes.speech.SpeechModelFile

/**
 * The models of Enhance audio's two speech restoration stages. The original carries them inside the app
 * (ThirdParty/LavaSR, ThirdParty/VoiceFixer, as Core ML); Takes downloads the published ONNX conversions of
 * the same checkpoints when the person adds the stage, as it downloads the subtitles' speech model, each file
 * checked by its SHA-256 ([app.takes.speech.SpeechModelFetcher]). The app's size does not change.
 *
 * The stages keep the original's model identities ([AudioModel.Lava], [AudioModel.VoiceFixer]), which name
 * its Core ML conversions: a project moves between the two apps with its stages, each played by its own
 * conversion of the same weights.
 */
object RestorationModels {
    /**
     * LavaSR v2 (Yatharth Sharma, Apache-2.0), the ONNX export published by Topping1 (LavaSR-ONNX, Apache-2.0),
     * release Alpha-v0.1: the backbone and the spectral head with their weights, 56,235,055 bytes. Its learned
     * tensors are those of the upstream checkpoint YouRec converts (SHA-256 d100db96…, docs/MODELS-ON-ANDROID.md).
     */
    val Lava: SpeechModel = run {
        val base = "https://github.com/Topping1/LavaSR-ONNX/releases/download/Alpha-v0.1"
        fun part(name: String, bytes: Long, sha256: String) = SpeechModelFile(name, "$base/$name", bytes, sha256)
        SpeechModel(
            id = "lavasr-v2-onnx@Alpha-v0.1",
            name = "LavaSR v2",
            folder = "lavasr-v2",
            kind = SpeechModel.Kind.LavaSR,
            files = listOf(
                part("enhancer_spec_head.onnx", 7_484L, "f66fd164c55fd1b07e5cea5e687c71522b192f452691128fd7ae4e6b26dbc683"),
                part("enhancer_backbone.onnx", 190_195L, "841e96d261dffdf1dc974f3d29e2cfcf1b16fd0b358749c1ace0bbfa1d4c8ddd"),
                part("enhancer_spec_head.onnx.data", 4_263_936L, "b855e309b027af9aa75285b97b345571b6bd695a30fde434d06c979d83885fd6"),
                part("enhancer_backbone.onnx.data", 51_773_440L, "a125a4ede7cfdd1073d906a3cadf2171a30be6a40f296ad28772e0ba258de8c5"),
            ),
            memory = 300_000_000L,
        )
    }

    /**
     * VoiceFixer (Haohe Liu et al.; code MIT, weights CC BY 4.0, Zenodo 5600188), the FP32 ONNX conversion
     * published by TigreGotico (audiosronnx-voicefixer) at revision aa259fa5: the analysis network, the vocoder
     * and the mel filterbank, 415,446,261 bytes. The filterbank is byte for byte the original's
     * VoiceFixer-Mel.bin after its NumPy header.
     */
    val VoiceFixer: SpeechModel = run {
        val base = "https://huggingface.co/TigreGotico/audiosronnx-voicefixer/resolve/aa259fa578b9d5f4c76fda3cd967f73542b0e15b"
        fun part(name: String, bytes: Long, sha256: String) = SpeechModelFile(name, "$base/$name", bytes, sha256)
        SpeechModel(
            id = "voicefixer-onnx@aa259fa578b9d5f4c76fda3cd967f73542b0e15b",
            name = "VoiceFixer",
            folder = "voicefixer",
            kind = SpeechModel.Kind.VoiceFixer,
            // Small first: a wrong address or a full disk shows before 415 MB are fetched.
            files = listOf(
                part(MEL_FILTERS, 524_928L, "d534ace99f19552fadd89d07d8cef0e3ff398b608ed2247c975c153b95b17f04"),
                part(VOCODER, 132_905_282L, "51b513f2e5510bd7d1e61d255015d47e2fc7b5af95eb35f25b6e5030446c5310"),
                part(ANALYSIS, 282_016_051L, "113d24062c5c9f87293a4e4771fbd4930136ccdff87ccbe6a6cb47726fc9cae9"),
            ),
            memory = 900_000_000L,
        )
    }

    internal const val ANALYSIS = "analysis.onnx"
    internal const val VOCODER = "vocoder.onnx"
    internal const val MEL_FILTERS = "mel_filters.npy"

    /**
     * Where each VoiceFixer graph fixes its input at 501 mel frames (five seconds, the converter's window): the
     * protobuf varint of that dimension, `f5 03`, which is read as 256, `80 02`, the original's context
     * (YouRec's `context256` runtime, YouRec/StudioRestorationProcessor.swift:260). Nothing else in either
     * graph depends on 501: the layers are convolutional and recurrent over time. Found and checked by reading
     * the graphs with the onnx package; with the change their SHA-256 become bada180e… and a80f621d…, the
     * values MyRec's manifest records for the same change.
     */
    internal val CONTEXT_PATCHES = mapOf(
        ANALYSIS to 282_015_972L,
        VOCODER to 132_905_242L,
    )
    internal val FRAMES_501 = byteArrayOf(0xf5.toByte(), 0x03)
    internal val FRAMES_256 = byteArrayOf(0x80.toByte(), 0x02)

    /** The model [kind]'s stage needs, or null for the stages that need none to download. */
    fun of(kind: AudioEffectKind): SpeechModel? = when (kind) {
        AudioEffectKind.StudioVoice -> Lava
        AudioEffectKind.StudioRestoration -> VoiceFixer
        else -> null
    }

    /** The model a stage saved with [model] is played with here, or null when it is not one of these. */
    fun of(model: AudioModel?): SpeechModel? = when (model) {
        AudioModel.Lava -> Lava
        AudioModel.VoiceFixer -> VoiceFixer
        else -> null
    }
}
