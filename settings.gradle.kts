pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "LiliyaCore"

include(":core")
include(":android-device-key")
include(":android-protected-model-staging")
include(":android-protected-model-engine-source")
