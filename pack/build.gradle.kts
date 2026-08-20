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
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * Regenerates the committed sprite index from a Minecraft client jar.
 *
 * Only needs re-running on a Minecraft version bump. Defaults to the
 * Prism Launcher jar for the version in the version catalog:
 *
 *   ./gradlew :libs:displaykit:pack:generateSpriteIndex
 *   ./gradlew :libs:displaykit:pack:generateSpriteIndex -PclientJar=/path/to/client.jar
 */
tasks.register<JavaExec>("generateSpriteIndex") {
    group = "displaykit"
    description = "Regenerate core/src/main/resources/displaykit/sprites.json from a client jar"

    val mcVersion = libs.versions.minecraft.get()
    val defaultJar = "${System.getProperty("user.home")}/Library/Application Support/PrismLauncher" +
        "/libraries/com/mojang/minecraft/$mcVersion/minecraft-$mcVersion-client.jar"
    val jarPath = (project.findProperty("clientJar") as String?) ?: defaultJar
    val output = rootProject.file("libs/displaykit/core/src/main/resources/displaykit/sprites.json")

    mainClass.set("io.schemat.displaykit.pack.gen.SpriteIndexGenerator")
    classpath = sourceSets["main"].runtimeClasspath
    args(jarPath, mcVersion, output.absolutePath)
}
