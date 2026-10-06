import java.time.LocalDate
import java.util.Properties

val androidVersionProperties = Properties().apply {
    val versionFile = layout.projectDirectory.file("version.properties").asFile
    require(versionFile.isFile) { "android-app/version.properties is required" }
    versionFile.inputStream().use { load(it) }
}

val liliyaVersionCodeText = androidVersionProperties.getProperty("versionCode")
    ?.trim()
    ?.takeIf { it.matches(Regex("""\d{8}""")) }
    ?: error("versionCode must use YYMMDDRR")

val versionYear = 2000 + liliyaVersionCodeText.substring(0, 2).toInt()
val versionMonth = liliyaVersionCodeText.substring(2, 4).toInt()
val versionDay = liliyaVersionCodeText.substring(4, 6).toInt()
val versionSequence = liliyaVersionCodeText.substring(6, 8).toInt()
require(versionSequence in 1..99) { "versionCode RR must be in 01..99" }
runCatching { LocalDate.of(versionYear, versionMonth, versionDay) }
    .getOrElse { error("versionCode YYMMDD must be a valid calendar date") }

val liliyaVersionCode = liliyaVersionCodeText.toInt()

val liliyaVersionName = androidVersionProperties.getProperty("versionName")
    ?.trim()
    ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
    ?: error("versionName must use MAJOR.MINOR.PATCH numeric format")

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
        versionCode = liliyaVersionCode
        versionName = liliyaVersionName
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
    implementation(project(":android-device-key"))
    implementation(project(":android-llama-cpp-engine"))
    implementation(project(":android-protected-model-staging"))
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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
}
