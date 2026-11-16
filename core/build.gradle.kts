plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// The long golden project (2,000 clips, 300 steps) is read whole several times by the migration tests: more than the default 512 MB.
tasks.test { maxHeapSize = "1g" }
