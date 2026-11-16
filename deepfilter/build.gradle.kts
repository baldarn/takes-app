import java.net.URI
import java.security.MessageDigest

plugins {
    // No version: the Android Gradle plugin is already on the classpath through the root
    // build file, and repeating its version here would be rejected.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.takes.deepfilter"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// ONNX Runtime runs the network. The two artifacts hold the same Java classes; the first
// brings the native library for phones, the second the one for this computer, which the
// unit tests need. Keep both on one version. This is a copy of the runtime of its own: the
// one inside sherpa-onnx (`speech/`) is linked into its library and not reachable from here.
val onnxRuntime = "1.32.0"

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntime")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    testImplementation(libs.junit)
}

// The tests that need the weights look here, or wherever
// TAKES_DFN3_MODEL points, and are skipped when the file is missing.
val modelFile = providers.environmentVariable("TAKES_DFN3_MODEL").map { file(it) }
    .orElse(layout.projectDirectory.file("models/deepfilter.onnx").asFile)

tasks.withType<Test>().configureEach {
    dependsOn("fetchModel")
    val model = modelFile.get()
    inputs.files(model).optional().withPropertyName("deepFilterModel")
    systemProperty("takes.deepfilter.model", model.absolutePath)
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
val fetchModel by tasks.registering {
    val target = layout.projectDirectory.file("models/deepfilter.onnx").asFile
    val url = "https://huggingface.co/soniqo/DeepFilterNet3-ONNX/resolve/63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx"
    val expected = "e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3"
    outputs.file(target)
    fun hash(file: File): String {
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
    onlyIf { !target.isFile || hash(target) != expected }
    doLast {
        target.parentFile.mkdirs()
        // Written beside the target and moved over it only once checked, so an interrupted
        // or altered download is never taken for the model.
        val partial = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val found = hash(partial)
        if (found != expected) {
            partial.delete()
            throw GradleException("$url has SHA-256 $found, expected $expected")
        }
        target.delete()
        if (!partial.renameTo(target)) throw GradleException("Cannot move $partial to $target")
    }
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
    dependsOn(fetchModel)
    model.set(layout.projectDirectory.file("models/deepfilter.onnx"))
    output.set(layout.buildDirectory.dir("generated/deepfilter-model"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(bundleModel, BundleModel::output)
    }
}
