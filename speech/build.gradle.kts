import java.net.URI
import java.security.MessageDigest

plugins {
    // No version: the Android Gradle plugin is already on the classpath through the root
    // build file, and repeating its version here would be rejected.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

// sherpa-onnx is not on Maven Central or Google Maven. Its Android library and its desktop
// libraries are files attached to the GitHub release, so the build downloads them once,
// checks their SHA-256 and unpacks them. Nothing of it is stored in the repository.
val sherpaVersion = "2.0.1"
val sherpaRelease = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion"
// The variant with ONNX Runtime linked into the one native library: a single file for each
// phone type, and no libonnxruntime.so that could clash with another copy in the app.
val sherpaAar = "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
val sherpaAarSha256 = "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
val sherpaAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// The same library for the computer that runs the unit tests.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val hostNative: Pair<String, String>? = when {
    "linux" in hostOs && hostArch in setOf("amd64", "x86_64") ->
        "linux-x64" to "744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f"
    "linux" in hostOs && hostArch == "aarch64" ->
        "linux-aarch64" to "c09d51e7190f5b8bcc20cf0d25bcb2e66324737ace5ce9d678ae37b8c79d6c2f"
    "mac" in hostOs && hostArch == "aarch64" ->
        "osx-aarch64" to "718a4ea4799341146ef9a0cc15daa70e6622ae4a16911952604035f396703499"
    "mac" in hostOs ->
        "osx-x64" to "617a6263b4793495ea6a5f882cfc5dbf9e57cf4ad1e126104bddf8cf90846f72"
    else -> null
}

// Outside build/, so that "clean" does not download 38 MB again.
val sherpaDir = layout.projectDirectory.dir(".sherpa-onnx/$sherpaVersion")

/** Downloads [url] to [target] unless a file with the right SHA-256 is already there. */
abstract class VerifiedDownload : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun run() {
        val file = target.get().asFile
        if (file.isFile && hash(file) == sha256.get()) return
        file.parentFile.mkdirs()
        val partial = File(file.path + ".part")
        URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val found = hash(partial)
        if (found != sha256.get()) {
            partial.delete()
            throw GradleException("${url.get()} has SHA-256 $found, expected ${sha256.get()}")
        }
        file.delete()
        if (!partial.renameTo(file)) throw GradleException("Cannot move $partial to $file")
    }
}

val downloadSherpaOnnx by tasks.registering(VerifiedDownload::class) {
    url.set("$sherpaRelease/$sherpaAar")
    sha256.set(sherpaAarSha256)
    target.set(sherpaDir.file(sherpaAar))
}

// classes.jar holds the Kotlin API of sherpa-onnx; jni/ holds its native libraries.
val unpackSherpaOnnx by tasks.registering(Sync::class) {
    from(zipTree(downloadSherpaOnnx.flatMap { it.target })) {
        include("classes.jar")
        sherpaAbis.forEach { include("jni/$it/libsherpa-onnx-jni.so") }
    }
    into(sherpaDir.dir("android"))
}
val sherpaClasses = files(sherpaDir.file("android/classes.jar")).builtBy(unpackSherpaOnnx)

android {
    namespace = "app.takes.speech"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += sherpaAbis }
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].jniLibs.srcDir(sherpaDir.dir("android/jni"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Packed into this module's library, so the app needs nothing but this module.
    implementation(sherpaClasses)
    testImplementation(libs.junit)
}

// The native libraries exist only after unpacking.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(unpackSherpaOnnx) }

val hostNativeDir = sherpaDir.dir("host")
if (hostNative != null) {
    val (platform, hash) = hostNative
    val jar = "sherpa-onnx-native-lib-$platform-$sherpaVersion.jar"
    val downloadHostNative by tasks.registering(VerifiedDownload::class) {
        url.set("$sherpaRelease/$jar")
        sha256.set(hash)
        target.set(sherpaDir.file(jar))
    }
    val unpackHostNative by tasks.registering(Sync::class) {
        from(zipTree(downloadHostNative.flatMap { it.target })) {
            include("sherpa-onnx/native/$platform/*")
            eachFile { path = name }
            includeEmptyDirs = false
        }
        into(hostNativeDir)
    }
    tasks.withType<Test>().configureEach { dependsOn(unpackHostNative) }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", hostNativeDir.asFile.absolutePath)
    // Where the recognition tests keep the models and recordings they download (about
    // 265 MB; Qwen3-ASR is used only if already there). Without it they are skipped and the build needs no network beyond Gradle's:
    //   ./gradlew :speech:testDebugUnitTest -Pspeech.models=/some/folder/outside/the/repository
    // -Pspeech.timedModel=<folder> names a Whisper base export with attention outputs
    // (base-encoder.int8.onnx, base-decoder.int8.onnx, base-tokens.txt) for the word-time tests.
    for (name in listOf("speech.models", "speech.timedModel")) {
        val value = providers.gradleProperty(name).orNull
        if (value != null) systemProperty(name, value)
        inputs.property(name, value ?: "")
    }
    // Temporary folders of the tests stay inside build/.
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    maxHeapSize = "1g"
    // The recognized text and the measured times are part of the result.
    testLogging { showStandardStreams = true }
}
