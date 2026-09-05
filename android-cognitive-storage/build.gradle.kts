plugins {
    id("com.android.library")
}

android {
    namespace = "pro.liliya.android.cognitivestorage"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":android-device-key"))
    implementation(project(":android-durable-persistence"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
