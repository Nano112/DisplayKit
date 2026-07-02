plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.schemat.displaykit"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.joml)
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
