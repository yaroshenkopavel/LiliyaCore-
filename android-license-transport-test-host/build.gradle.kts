plugins {
    id("com.android.application")
}

android {
    namespace = "pro.liliya.android.licensetransport.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "pro.liliya.android.licensetransport"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
    implementation(project(":license-transport-client"))

    androidTestImplementation(project(":core"))
    androidTestImplementation(project(":license-transport-client"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
