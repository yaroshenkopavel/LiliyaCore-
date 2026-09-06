import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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
            kotlin.srcDir("../android-offline-semantic-provider/src/androidTest/kotlin")
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

dependencies {
    implementation(project(":android-offline-semantic-provider"))

    androidTestImplementation(project(":core"))
    androidTestImplementation(project(":android-offline-semantic-provider"))
    androidTestImplementation(project(":android-llama-cpp-engine"))
    androidTestImplementation(project(":android-protected-model-staging"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}


val providerCompileDebugKotlin =
    project(":android-offline-semantic-provider")
        .tasks
        .named<KotlinJvmCompile>("compileDebugKotlin")

tasks.named<KotlinJvmCompile>("compileDebugAndroidTestKotlin") {
    dependsOn(providerCompileDebugKotlin)

    compilerOptions {
        freeCompilerArgs.add(
            providerCompileDebugKotlin
                .flatMap { it.destinationDirectory }
                .map { "-Xfriend-paths=${it.asFile.absolutePath}" }
        )
    }
}
