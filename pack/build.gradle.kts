plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.schemat.displaykit"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":libs:displaykit:core"))
    implementation(libs.joml)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
