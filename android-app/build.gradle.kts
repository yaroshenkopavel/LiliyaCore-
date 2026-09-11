plugins {
    id("com.android.application")
}

android {
    namespace = "pro.liliya.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "pro.liliya.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":android-runtime"))
    implementation(project(":license-transport-client"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(project(":core"))
    androidTestImplementation(project(":android-runtime"))
    androidTestImplementation(project(":android-device-key"))
    androidTestImplementation(project(":android-llama-cpp-engine"))
    androidTestImplementation(project(":android-offline-semantic-provider"))
    androidTestImplementation(project(":android-protected-model-staging"))
    androidTestImplementation(project(":protected-model-packager"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.bouncycastle:bcprov-jdk18on:1.81")
}
