import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Upload key for Google Play. keystore.properties is not in git; without it the release
// build is left unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.takes"
    compileSdk = 36
    // The NDK the native modules are built with: also what strips their libraries for the APK and keeps their
    // symbol tables for Play.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.takes"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.17"
        // The pixel tests' runner; the sound gate's is declared beside it in the test manifest.
        testInstrumentationRunner = "app.takes.pixels.TransitionPixelRunner"
    }
    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // The development build installs beside the store one and is told apart by its name and icon.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // The native libraries' symbol tables go into the bundle, so Play can show readable native crash stacks.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // The release as it ships, under its own name and icon, to try on a phone beside the store build.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            versionNameSuffix = "-smoke"
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The app speaks English and Italian; without this the libraries bring ninety more languages along.
    androidResources {
        localeFilters += listOf("en", "it")
        // The person-mask and face models are mapped from the APK as they lie there (PersonMasks.kt, FaceFinder.kt):
        // stored, not deflated.
        noCompress += "tflite"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":rnnoise"))
    implementation(project(":speech"))
    implementation(project(":deepfilter"))
    implementation(project(":stabilization"))
    implementation(project(":restoration"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
    // The person mask of four video effects, on the CPU (docs/SEGMENTATION-PROBE.md), and the faces Follow face and
    // the camera moves follow (FaceFinder.kt). No litert-gpu: the CPU is as fast.
    implementation(libs.litert)
}
