package app.takes.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stages of the chain as data: kinds, defaults, what is valid, and the saved form (YouRec/Project.swift:63-139). */
class AudioEffectTest {
    private val chain = ListSerializer(AudioEffect.serializer())

    /**
     * A chain as the original writes it: a stage is an object with these keys
     * (Project.swift:119-126), `kind` is the bare name (Project.swift:82-86),
     * `model` is absent when there is none, whole numbers have no fraction,
     * and ids are upper-case UUIDs. The last stage is the one a future
     * version might add (Tests/CoreTests.swift:153-154).
     */
    private val original = """
        [{"id":"5B0C2A0E-3C1B-4F0E-9A49-0D6A1F6E2C11","kind":"voiceIsolation","enabled":true,"amount":1,"frequency":1800,"implementationVersion":1,
          "model":{"id":"rnnoise-v0.1-cdf196b1","weightsSHA256":"f0cdb52b30501aab489f90fedbc7a023c719d91b337db2da7f26fc3036556b95","runtime":"yourec-rnnoise-c-v1"}},
         {"id":"7E1D9C55-0E0B-4C52-8E63-0B8B4E3F9A02","kind":"studioVoice","enabled":false,"amount":0.35,"frequency":1800,"implementationVersion":1,
          "model":{"id":"lavasr-v2-33ac0408","weightsSHA256":"1ef7557e72c9b2ee78ccb35cf3ab3400c0c73be1838ab16ff7dde52934f4d2cf","runtime":"yourec-coreml-fp32-lava-dsp-v1"}},
         {"id":"A3F4B1D2-6C7E-4A10-B5D8-2F9E0C1A7B33","kind":"highPass","enabled":true,"amount":0,"frequency":80,"implementationVersion":1},
         {"id":"C9D8E7F6-1A2B-4C3D-8E4F-5A6B7C8D9E44","kind":"equalizer","enabled":true,"amount":-4.5,"frequency":2500,"implementationVersion":1},
         {"id":"D1E2F3A4-B5C6-4D7E-9F80-1A2B3C4D5E55","kind":"gain","enabled":true,"amount":-6,"frequency":1800,"implementationVersion":1},
         {"id":"E5F6A7B8-C9D0-4E1F-A2B3-C4D5E6F7A866","kind":"futureDereverbV9","enabled":true,"amount":42,"frequency":1800,"implementationVersion":9}]
    """.trimIndent()

    /** JSON with every number as a double, so that 80 and 80.0 compare equal. */
    private fun numbers(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { numbers(it.value) })
        is JsonArray -> JsonArray(element.map { numbers(it) })
        is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null) JsonPrimitive(element.doubleOrNull) else element
    }

    @Test fun theOriginalsChainIsReadAndWrittenBackWithTheSameNamesAndValues() {
        val effects = documentJson.decodeFromString(chain, original)
        assertEquals(
            listOf(
                AudioEffect("5B0C2A0E-3C1B-4F0E-9A49-0D6A1F6E2C11", AudioEffectKind.VoiceIsolation, amount = 1.0, model = AudioModel.RNNoise),
                AudioEffect("7E1D9C55-0E0B-4C52-8E63-0B8B4E3F9A02", AudioEffectKind.StudioVoice, enabled = false, amount = 0.35, model = AudioModel.Lava),
                AudioEffect("A3F4B1D2-6C7E-4A10-B5D8-2F9E0C1A7B33", AudioEffectKind.HighPass, frequency = 80.0),
                AudioEffect("C9D8E7F6-1A2B-4C3D-8E4F-5A6B7C8D9E44", AudioEffectKind.Equalizer, amount = -4.5, frequency = 2500.0),
                AudioEffect("D1E2F3A4-B5C6-4D7E-9F80-1A2B3C4D5E55", AudioEffectKind.Gain, amount = -6.0),
                AudioEffect("E5F6A7B8-C9D0-4E1F-A2B3-C4D5E6F7A866", AudioEffectKind.Unavailable("futureDereverbV9"), amount = 42.0, implementationVersion = 9),
            ),
            effects,
        )
        val written = documentJson.encodeToString(chain, effects)
        assertEquals(numbers(documentJson.parseToJsonElement(original)), numbers(documentJson.parseToJsonElement(written)))
        assertEquals(effects, documentJson.decodeFromString(chain, written))
    }

    @Test fun aStageIsWrittenWithEveryFieldAndNoModelWhenItHasNone() {
        assertEquals(
            """{"id":"G","kind":"gain","enabled":true,"amount":-6.0,"frequency":1800.0,"implementationVersion":1}""",
            documentJson.encodeToString(AudioEffect.serializer(), AudioEffect(id = "G", kind = AudioEffectKind.Gain, amount = -6.0)),
        )
        assertEquals(
            """{"id":"N","kind":"voiceIsolation","enabled":true,"amount":1.0,"frequency":1800.0,"implementationVersion":1,""" +
                """"model":{"id":"deepfilternet3-dfc12319","weightsSHA256":"1114ff972e25679e7f9707da85eee62659e1bdf50199035ecacba12e4ec0425d",""" +
                """"runtime":"yourec-dfn3-coreml-cpu-v1-dsp3dd66dea-milc2141d48"}}""",
            documentJson.encodeToString(AudioEffect.serializer(), AudioEffect.make(AudioEffectKind.VoiceIsolation, "N").copy(model = AudioModel.DeepFilter)),
        )
    }

    @Test fun aNewStageHasAnUpperCaseUUIDLikeTheOriginals() {
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(AudioEffect.make(AudioEffectKind.Gain).id))
    }

    @Test fun thereAreSixKindsInTheOrderOfTheMenu() {
        // Project.swift:64-66.
        assertEquals(listOf("voiceIsolation", "studioVoice", "studioRestoration", "highPass", "equalizer", "gain"), AudioEffectKind.all.map { it.rawValue })
        // Project.swift:68.
        assertEquals(listOf(true, true, true, false, false, false), AudioEffectKind.all.map { it.isNeural })
        // Project.swift:87-96.
        assertEquals(
            listOf("Noise reduction", "Speech detail restoration", "Studio restoration", "Low-cut filter", "Voice EQ", "Output gain"),
            AudioEffectKind.all.map { it.title },
        )
        assertTrue(AudioEffectKind.all.all { it.isSupported && AudioEffectKind.of(it.rawValue) === it })
    }

    @Test fun anUnknownKindKeepsItsName() {
        // Project.swift:80-83; CoreTests.swift:153-158.
        val future = AudioEffectKind.of("futureDereverbV9")
        assertEquals(AudioEffectKind.Unavailable("futureDereverbV9"), future)
        assertEquals("futureDereverbV9", future.rawValue)
        assertEquals("Unavailable filter", future.title)
        assertFalse(future.isSupported)
        assertFalse(future.isNeural)
        val unknown = AudioEffect(kind = future, amount = 42.0, implementationVersion = 9)
        assertFalse(unknown.supported)
        // Its settings are not judged: the project stays valid and the stage is kept.
        assertTrue(unknown.valid)
        assertEquals(unknown, documentJson.decodeFromString(AudioEffect.serializer(), documentJson.encodeToString(AudioEffect.serializer(), unknown)))
    }

    @Test fun eachKindStartsFromItsOwnSettings() {
        // Project.swift:134-138.
        fun made(kind: AudioEffectKind) = AudioEffect.make(kind, "X")
        assertEquals(AudioEffect("X", AudioEffectKind.VoiceIsolation, true, 1.0, 1800.0, 1, AudioModel.RNNoise), made(AudioEffectKind.VoiceIsolation))
        assertEquals(AudioEffect("X", AudioEffectKind.StudioVoice, true, 0.35, 1800.0, 1, AudioModel.Lava), made(AudioEffectKind.StudioVoice))
        assertEquals(AudioEffect("X", AudioEffectKind.StudioRestoration, true, 1.0, 1800.0, 1, AudioModel.VoiceFixer), made(AudioEffectKind.StudioRestoration))
        assertEquals(AudioEffect("X", AudioEffectKind.HighPass, true, 0.0, 80.0, 1, null), made(AudioEffectKind.HighPass))
        assertEquals(AudioEffect("X", AudioEffectKind.Equalizer, true, 0.0, 1800.0, 1, null), made(AudioEffectKind.Equalizer))
        assertEquals(AudioEffect("X", AudioEffectKind.Gain, true, 0.0, 1800.0, 1, null), made(AudioEffectKind.Gain))
        // Project.swift:121-125: a stage built by hand.
        assertEquals(AudioEffect("X", AudioEffectKind.Gain, true, 0.0, 1800.0, 1, null), AudioEffect(id = "X", kind = AudioEffectKind.Gain))
        assertTrue(AudioEffectKind.all.all { AudioEffect.make(it).supported && AudioEffect.make(it).valid })
    }

    @Test fun aModelStageIsSupportedOnlyWithItsOwnModel() {
        // Project.swift:127-133.
        val isolation = AudioEffect.make(AudioEffectKind.VoiceIsolation)
        assertTrue(isolation.copy(model = AudioModel.DeepFilter).supported)
        assertFalse(isolation.copy(model = AudioModel.Lava).supported)
        assertFalse(isolation.copy(model = null).supported)
        assertFalse(isolation.copy(model = AudioModel.RNNoise.copy(runtime = "another-runtime")).supported)
        // At amount 0 the model is never asked.
        assertTrue(isolation.copy(model = null, amount = 0.0).supported)
        val studio = AudioEffect.make(AudioEffectKind.StudioVoice)
        assertFalse(studio.copy(model = AudioModel.VoiceFixer).supported)
        assertTrue(studio.copy(model = null, amount = 0.0).supported)
        val restoration = AudioEffect.make(AudioEffectKind.StudioRestoration)
        assertFalse(restoration.copy(model = AudioModel.Lava).supported)
        assertTrue(restoration.copy(model = AudioModel.Lava, amount = 0.0).supported)
        // Filters need none, and only version 1 is known.
        assertTrue(AudioEffect(kind = AudioEffectKind.Gain, model = AudioModel.Lava).supported)
        assertFalse(AudioEffect(kind = AudioEffectKind.Gain, implementationVersion = 2).supported)
    }

    @Test fun whatAProjectMayHold() {
        // Project.swift:520-522, TrackTimeline.swift:1244.
        val gain = AudioEffect(kind = AudioEffectKind.Gain)
        assertTrue(gain.copy(amount = 24.0).valid)
        assertTrue(gain.copy(amount = -24.0).valid)
        assertFalse(gain.copy(amount = 24.1).valid)
        assertFalse(gain.copy(amount = Double.NaN).valid)
        assertTrue(gain.copy(frequency = 20.0).valid)
        assertTrue(gain.copy(frequency = 20000.0).valid)
        assertFalse(gain.copy(frequency = 19.9).valid)
        assertFalse(gain.copy(frequency = 20000.5).valid)
        assertFalse(gain.copy(frequency = Double.POSITIVE_INFINITY).valid)
        assertFalse(gain.copy(implementationVersion = 0).valid)
        // A version from the future is kept with whatever amount it has.
        assertTrue(gain.copy(implementationVersion = 2, amount = 99.0).valid)
        val isolation = AudioEffect.make(AudioEffectKind.VoiceIsolation)
        assertTrue(isolation.copy(amount = 0.0).valid)
        assertFalse(isolation.copy(amount = 1.01).valid)
        assertFalse(isolation.copy(amount = -0.01).valid)
        assertEquals(0.0..1.0, AudioEffect.amountRange(AudioEffectKind.StudioVoice))
        assertEquals(-24.0..24.0, AudioEffect.amountRange(AudioEffectKind.Equalizer))
        // TrackTimeline.swift:1243: ids are unique within a chain.
        assertTrue(AudioChain.valid(listOf(gain, gain.copy(id = "other"))))
        assertFalse(AudioChain.valid(listOf(gain, gain)))
        assertFalse(AudioChain.valid(listOf(gain.copy(amount = 30.0))))
        assertTrue(AudioChain.valid(emptyList()))
    }

    @Test fun aChainOnPartOfASound() {
        val region = AudioEnhancement(id = "R", start = 1.0, end = 2.5, effects = listOf(AudioEffect(id = "G", kind = AudioEffectKind.Gain, amount = 3.0)))
        assertTrue(region.valid)
        assertFalse(region.copy(end = 1.0).valid)
        assertFalse(region.copy(start = -0.5).valid)
        assertFalse(region.copy(effects = region.effects + region.effects).valid)
        val written = documentJson.encodeToString(AudioEnhancement.serializer(), region)
        assertEquals(
            """{"id":"R","start":1.0,"end":2.5,"effects":[{"id":"G","kind":"gain","enabled":true,"amount":3.0,"frequency":1800.0,"implementationVersion":1}]}""",
            written,
        )
        assertEquals(region, documentJson.decodeFromString(AudioEnhancement.serializer(), written))
        assertNull(region.effects[0].model)
    }
}
