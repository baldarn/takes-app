plugins {
    // No version: the Android Gradle plugin is already on the classpath through the root
    // build file, and repeating its version here would be rejected.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.takes.stabilization"
    compileSdk = 36
    // Pinned so every machine links the same way. r28 and newer align shared libraries
    // to 16 KB pages by default, which Android 15+ devices with 16 KB pages require.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
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

// The unit tests run on this computer, not on a phone, so they need the same C++ code built
// for this computer. It is the same CMake project the Android build uses, compiled with
// the system C++ compiler and the CMake and Ninja that come with the Android SDK, with the
// original's own test of the estimator beside it.
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
    inputs.dir("src/test/cpp")
    outputs.dir(hostNativeDir)
    doFirst { commandLine("${cmakeBin.get().asFile}/cmake", "--build", hostNativeDir.get().asFile.absolutePath) }
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    inputs.dir("src/main/cpp")
    inputs.dir("src/test/cpp")
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
    systemProperty("stabilization.motionTests", hostNativeDir.get().file("stabilization_motion_tests").asFile.absolutePath)
    // The measured motions are part of the result; print them with the test outcome.
    testLogging { showStandardStreams = true }
}
