import org.gradle.jvm.tasks.Jar

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

    // Test-only coexistence and full real-engine proof with the frozen generation engine.
    // The ARM64 resource-only gate opts out so it measures the semantic provider in isolation
    // and does not rebuild the unrelated frozen generation native toolchain.
    if (!project.providers.gradleProperty("semanticSkipGenerationTestDependency").isPresent) {
        androidTestImplementation(project(":android-llama-cpp-engine"))
        androidTestImplementation(project(":android-protected-model-staging"))
    }
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}


tasks.register("reportSemanticRuntimeFootprint") {
    dependsOn("assembleRelease")
    doLast {
        val runtime = configurations.getByName("releaseRuntimeClasspath")
        val resolved = runtime.resolvedConfiguration.resolvedArtifacts
            .sortedWith(compareBy({ it.moduleVersion.id.group }, { it.name }, { it.moduleVersion.id.version }))

        val releaseAar = layout.buildDirectory
            .file("outputs/aar/android-offline-semantic-provider-release.aar")
            .get()
            .asFile
        check(releaseAar.isFile) { "release semantic provider AAR is missing" }

        val selected = resolved.filter {
            it.moduleVersion.id.group == "com.microsoft.onnxruntime"
        }
        check(selected.any { it.name == "onnxruntime-android" }) {
            "pinned onnxruntime-android artifact is missing from release runtime classpath"
        }
        check(selected.any { it.name == "onnxruntime-extensions-android" }) {
            "pinned onnxruntime-extensions-android artifact is missing from release runtime classpath"
        }

        val report = layout.buildDirectory
            .file("reports/semantic-runtime-footprint.json")
            .get()
            .asFile
        report.parentFile.mkdirs()

        fun escaped(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        val externalBytes = selected.sumOf { it.file.length() }
        val json = buildString {
            appendLine("{")
            appendLine("  \"scope\": \"semantic-release-runtime-input-lower-bound\",")
            appendLine("  \"finalApkEvidence\": false,")
            appendLine("  \"providerReleaseAarBytes\": ${releaseAar.length()},")
            appendLine("  \"onnxRuntimeArtifactsBytes\": $externalBytes,")
            appendLine("  \"combinedLowerBoundBytes\": ${releaseAar.length() + externalBytes},")
            appendLine("  \"artifacts\": [")
            selected.forEachIndexed { index, artifact ->
                val id = artifact.moduleVersion.id
                val comma = if (index == selected.lastIndex) "" else ","
                appendLine(
                    "    {\"group\":\"${escaped(id.group)}\",\"name\":\"${escaped(artifact.name)}\",\"version\":\"${escaped(id.version)}\",\"bytes\":${artifact.file.length()}}$comma"
                )
            }
            appendLine("  ]")
            appendLine("}")
        }
        report.writeText(json)
        println("SEMANTIC_RUNTIME_FOOTPRINT_REPORT=${report.absolutePath}")
        println(json)
    }
}


val firebaseI8AndroidTestClassesJar by tasks.registering(Jar::class) {
    dependsOn("compileDebugAndroidTestKotlin")
    archiveBaseName.set("firebase-i8-android-test-classes")
    archiveClassifier.set("debug")

    from(layout.buildDirectory.dir("tmp/kotlin-classes/debugAndroidTest"))
}
