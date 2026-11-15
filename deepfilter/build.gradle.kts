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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// ONNX Runtime runs the network. The two artifacts hold the same Java classes; the first
// brings the native library for phones, the second the one for this computer, which the
// unit tests need. Keep both on one version.
val onnxRuntime = "1.32.0"

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntime")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    testImplementation(libs.junit)
}

// The network's weights are not in git. The tests that need them look here, or wherever
// TAKES_DFN3_MODEL points, and are skipped when the file is missing.
val modelFile = providers.environmentVariable("TAKES_DFN3_MODEL").map { file(it) }
    .orElse(layout.projectDirectory.file("models/deepfilter.onnx").asFile)

tasks.withType<Test>().configureEach {
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

// `./gradlew :deepfilter:fetchModel` downloads the weights for the tests. Nothing else
// depends on it, so building and testing never use the network.
val fetchModel by tasks.registering {
    val target = layout.projectDirectory.file("models/deepfilter.onnx").asFile
    val url = "https://huggingface.co/soniqo/DeepFilterNet3-ONNX/resolve/63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx"
    val expected = "e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3"
    outputs.file(target)
    doLast {
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == expected) { "Downloaded model has SHA-256 $hash, expected $expected" }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}
