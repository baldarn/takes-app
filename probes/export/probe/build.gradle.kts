plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.takes.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.takes.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// The app's transition effect and its compositor, compiled from the app's own file, which is
// only read: the probe runs the code the exporter runs.
val appSources = tasks.register<Copy>("appSources") {
    from("../../../app/src/main/kotlin") { include("app/takes/TransitionRendering.kt") }
    into(layout.buildDirectory.dir("app-sources"))
}
android.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("app-sources"))
tasks.named("preBuild") { dependsOn(appSources) }

dependencies {
    implementation(project(":core"))
    // For the annotations the app's file uses.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    // The muxer the transformer already brings along, named so the joiner may use it.
    implementation("androidx.media3:media3-muxer:${libs.versions.media3.get()}")
}
