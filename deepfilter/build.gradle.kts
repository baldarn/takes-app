import java.net.URI
import java.security.MessageDigest

plugins {
    // No version: the Android Gradle plugin is already on the classpath through the root
    // build file, and repeating its version here would be rejected.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

// ONNX Runtime runs the network, but this module brings no copy of it: its small native
// library (src/main/cpp) calls the C API of the libonnxruntime.so that sherpa-onnx brings
// (speech/), version 1.28.2, so that the app carries the runtime once. The unit tests use
// sherpa-onnx's copy for this computer, which the speech module unpacks.
evaluationDependsOn(":speech")
// Its C headers are kept in src/main/cpp/onnxruntime (see the README there).
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "app.takes.deepfilter"
    compileSdk = 36
    // The same NDK as rnnoise/: r28 and newer align shared libraries to 16 KB pages.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        // The ABIs sherpa-onnx, and so ONNX Runtime, is built for.
        ndk { abiFilters += abis }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}

/** Downloads [url] to [target] unless a file with the right SHA-256 is already there. */
abstract class VerifiedDownload : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    init {
        // Up to date only when the file is there and whole; otherwise fetched again.
        outputs.upToDateWhen { target.get().asFile.let { it.isFile && hash(it) == sha256.get() } }
    }

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
        // Written beside the target and moved over it only once checked, so an interrupted
        // or altered download is never taken for the real file.
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

// The tests run on this computer, not on a phone, so they need the native library built for
// this computer: the same CMake project, compiled with the system C++ compiler and the CMake
// and Ninja that come with the Android SDK.
val hostNativeDir = layout.buildDirectory.dir("host-native")
val cmakeBin = androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.22.1/bin") }
val testJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

val configureHostNative by tasks.registering(Exec::class) {
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(hostNativeDir.map { it.file("build.ninja") })
    val source = file("src/main/cpp").absolutePath
    doFirst {
        val bin = cmakeBin.get().asFile
        commandLine(
            "$bin/cmake", "-S", source, "-B", hostNativeDir.get().asFile.absolutePath, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$bin/ninja", "-DCMAKE_BUILD_TYPE=Release", "-DHOST_JAVA_HOME=${testJdk.get()}",
        )
    }
}

val buildHostNative by tasks.registering(Exec::class) {
    dependsOn(configureHostNative)
    inputs.dir("src/main/cpp")
    outputs.dir(hostNativeDir)
    doFirst { commandLine("${cmakeBin.get().asFile}/cmake", "--build", hostNativeDir.get().asFile.absolutePath) }
}

// sherpa-onnx's ONNX Runtime for this computer, unpacked by the speech module; missing on a
// computer sherpa-onnx publishes no library for, where the tests of the network are skipped.
val speech = project(":speech")
val hostRuntimeDir = speech.extra["onnxRuntimeHostDir"] as Directory
val hostRuntimeTask = speech.tasks.findByName("unpackHostNative")

// The tests that need the weights look here, or wherever
// TAKES_DFN3_MODEL points, and are skipped when the file is missing.
val modelFile = providers.environmentVariable("TAKES_DFN3_MODEL").map { file(it) }
    .orElse(layout.projectDirectory.file("models/deepfilter.onnx").asFile)

tasks.withType<Test>().configureEach {
    dependsOn("fetchModel", buildHostNative)
    if (hostRuntimeTask != null) dependsOn(hostRuntimeTask)
    val model = modelFile.get()
    inputs.files(model).optional().withPropertyName("deepFilterModel")
    inputs.dir("src/main/cpp")
    systemProperty("takes.deepfilter.model", model.absolutePath)
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
    doFirst {
        val runtime = hostRuntimeDir.asFile.listFiles()?.firstOrNull { it.name.startsWith("libonnxruntime") }
        if (runtime != null) systemProperty("takes.onnxruntime.library", runtime.absolutePath)
    }
    // The measured levels are part of the result; print them with the test outcome, and
    // say so when a test was skipped for want of the model.
    testLogging {
        showStandardStreams = true
        events("skipped", "failed")
    }
}

// The network's weights, fetched once (`./gradlew :deepfilter:fetchModel`, which the build
// runs) into models/, outside build/ so that "clean" does not download them again, and
// checked by SHA-256. They are not in git. The app carries them inside the APK as a Java
// resource next to streaming-graph.onnx ([DeepFilterModel.bundled]): the phone never
// downloads anything.
val fetchModel by tasks.registering(VerifiedDownload::class) {
    url.set("https://huggingface.co/soniqo/DeepFilterNet3-ONNX/resolve/63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx")
    sha256.set("e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3")
    target.set(layout.projectDirectory.file("models/deepfilter.onnx"))
}

// The weights as a resource of the library, at app/takes/deepfilter/deepfilter.onnx.
abstract class BundleModel : DefaultTask() {
    @get:InputFile abstract val model: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val folder = output.get().asFile.resolve("app/takes/deepfilter")
        folder.deleteRecursively()
        folder.mkdirs()
        model.get().asFile.copyTo(folder.resolve("deepfilter.onnx"), overwrite = true)
    }
}

val bundleModel by tasks.registering(BundleModel::class) {
    model.set(fetchModel.flatMap { it.target })
    output.set(layout.buildDirectory.dir("generated/deepfilter-model"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(bundleModel, BundleModel::output)
    }
}
