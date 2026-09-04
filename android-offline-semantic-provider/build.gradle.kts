plugins {
    id("com.android.library")
}

android {
    namespace = "pro.liliya.android.semanticprovider"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.12.4")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    // Test-only coexistence proof with the already frozen generation engine.
    androidTestImplementation(project(":android-llama-cpp-engine"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
