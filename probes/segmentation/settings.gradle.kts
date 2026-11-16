// A build of its own, not part of the app's: it borrows the app's version catalog, so the probe
// compiles with the same Android and Kotlin plugins as the app, and changes nothing of the app.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "TakesSegmentationProbe"
include(":probe")
