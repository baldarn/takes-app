plugins {
    // No version: the Android Gradle plugin is already on the classpath through the root
    // build file, and repeating its version here would be rejected.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

// The speech restoration stages of Enhance audio: LavaSR v2 and VoiceFixer. Their signal processing is
// in core (app.takes.core.SpeechDetailChannel, StudioRestorationChannel); here are their models, which the
// phone downloads when asked (speech/'s fetcher), and their networks, run by the ONNX Runtime the app
// already carries, through deepfilter/'s bridge to it. No native code of its own.
evaluationDependsOn(":deepfilter")

android {
    namespace = "app.takes.restoration"
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

dependencies {
    api(project(":core"))
    api(project(":speech"))
    implementation(project(":deepfilter"))
    testImplementation(libs.junit)
}

// The tests run the networks on this computer with deepfilter/'s native library built for it and
// sherpa-onnx's ONNX Runtime for it, as deepfilter/'s own tests do. The models are large and not in
// git: the tests that need them are skipped unless -Prestoration.models=<folder> names a folder,
// outside the repository, where they are downloaded once (about 470 MB).
val deepfilter = project(":deepfilter")
val speech = project(":speech")
val hostRuntimeDir = speech.extra["onnxRuntimeHostDir"] as Directory
tasks.withType<Test>().configureEach {
    dependsOn(deepfilter.tasks.named("buildHostNative"))
    speech.tasks.findByName("unpackHostNative")?.let { dependsOn(it) }
    systemProperty("java.library.path", deepfilter.layout.buildDirectory.dir("host-native").get().asFile.absolutePath)
    val models = providers.gradleProperty("restoration.models").orNull
    if (models != null) systemProperty("restoration.models", models)
    inputs.property("restoration.models", models ?: "")
    doFirst {
        val runtime = hostRuntimeDir.asFile.listFiles()?.firstOrNull { it.name.startsWith("libonnxruntime") }
        if (runtime != null) systemProperty("takes.onnxruntime.library", runtime.absolutePath)
    }
    maxHeapSize = "2g"
    // The measured speeds and levels are part of the result.
    testLogging {
        showStandardStreams = true
        events("skipped", "failed")
    }
}
