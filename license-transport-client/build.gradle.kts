plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.7")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
