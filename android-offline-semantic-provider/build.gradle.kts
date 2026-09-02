plugins {
    id("com.android.library")
}

android {
    namespace = "pro.liliya.android.semanticprovider"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
