plugins {
    id("com.android.application")
}

android {
    namespace = "pro.liliya.android.semanticprovider.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "pro.liliya.android.semanticprovider"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("../android-offline-semantic-provider/src/androidTest/assets")
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


val providerFirebaseI8TestJar =
    project(":android-offline-semantic-provider")
        .tasks
        .named("firebaseI8AndroidTestClassesJar")


dependencies {
    implementation(project(":android-offline-semantic-provider"))

    androidTestImplementation(project(":core"))
    androidTestImplementation(project(":android-offline-semantic-provider"))
    androidTestImplementation(files(providerFirebaseI8TestJar))
    androidTestImplementation(project(":android-llama-cpp-engine"))
    androidTestImplementation(project(":android-protected-model-staging"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
