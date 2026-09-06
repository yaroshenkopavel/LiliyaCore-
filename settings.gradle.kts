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
include(":android-llama-cpp-engine")
include(":android-offline-semantic-provider")
include(":android-durable-persistence")
include(":android-license-state")

include(":android-cognitive-storage")

include(":android-semantic-test-host")
