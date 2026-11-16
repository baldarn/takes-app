plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.takes.probe.segmentation"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.takes.probe.segmentation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The models are pushed to the files folder by tools/run.sh, not packaged.
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.core.ktx)
    // The two runtimes the probe compares (docs/SEGMENTATION-PROBE.md). Pinned here, not in the
    // app's catalog: the app takes one of them only after the probe.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
