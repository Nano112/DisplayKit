plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "io.schemat.displaykit"
version = providers.gradleProperty("displaykitVersion").orElse("0.2.0").get()

val displayKitRootDir = rootProject.file("libs/displaykit")
    .takeIf { it.resolve("LICENSE").isFile }
    ?: rootProject.projectDir
val coreProjectPath = if (rootProject.findProject(":libs:displaykit:core") != null) {
    ":libs:displaykit:core"
} else {
    ":core"
}

base {
    archivesName.set("displaykit-pack")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // SpriteId and SpriteEntry occur in public pack APIs.
    api(project(coreProjectPath))
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("displayKitPack") {
            // Gradle 9 removed a ProjectDependency method still used by the
            // Kotlin 2.1 publication bridge. Publish the concrete artifacts
            // and spell out the small runtime graph until that bridge is
            // upgraded; this keeps publishToMavenLocal usable today.
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar"))
            artifactId = "displaykit-pack"
            pom {
                name.set("DisplayKit Pack")
                description.set("Deterministic sprite and font resource-pack generation for DisplayKit")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                url.set("https://github.com/Nano112/DisplayKit")
                scm {
                    connection.set("scm:git:https://github.com/Nano112/DisplayKit.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Nano112/DisplayKit.git")
                    url.set("https://github.com/Nano112/DisplayKit")
                }
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    fun dependency(group: String, artifact: String, dependencyVersion: String) {
                        val node = dependencies.appendNode("dependency")
                        node.appendNode("groupId", group)
                        node.appendNode("artifactId", artifact)
                        node.appendNode("version", dependencyVersion)
                        node.appendNode("scope", "compile")
                    }
                    dependency("io.schemat.displaykit", "displaykit-core", project.version.toString())
                    dependency("com.google.code.gson", "gson", "2.10.1")
                    dependency("org.jetbrains.kotlin", "kotlin-stdlib", libs.versions.kotlin.get())
                }
            }
        }
    }
}

tasks.processResources {
    from(displayKitRootDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_displaykit" }
    }
    from(displayKitRootDir.resolve("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
        rename { "THIRD_PARTY_NOTICES_displaykit" }
    }
    from(displayKitRootDir.resolve("OFL-1.1.txt")) {
        into("META-INF")
        rename { "OFL-1.1_displaykit" }
    }
}

tasks.named<Jar>("sourcesJar") {
    from(displayKitRootDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_displaykit" }
    }
    from(displayKitRootDir.resolve("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
        rename { "THIRD_PARTY_NOTICES_displaykit" }
    }
    from(displayKitRootDir.resolve("OFL-1.1.txt")) {
        into("META-INF")
        rename { "OFL-1.1_displaykit" }
    }
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
    val output = displayKitRootDir.resolve("core/src/main/resources/displaykit/sprites.json")

    mainClass.set("io.schemat.displaykit.pack.gen.SpriteIndexGenerator")
    classpath = sourceSets["main"].runtimeClasspath
    args(jarPath, mcVersion, output.absolutePath)
}

/**
 * Regenerates the committed nine-slice crops from a Minecraft client jar.
 *
 * Only needs re-running on a Minecraft version bump, alongside
 * generateSpriteIndex. These are the only image bytes DisplayKit ships.
 *
 *   ./gradlew :libs:displaykit:pack:generateSpriteSlices
 *   ./gradlew :libs:displaykit:pack:generateSpriteSlices -PclientJar=/path/to/client.jar
 */
tasks.register<JavaExec>("generateSpriteSlices") {
    group = "displaykit"
    description = "Regenerate pack/src/main/resources/displaykit/slices from a client jar"

    val mcVersion = libs.versions.minecraft.get()
    val defaultJar = "${System.getProperty("user.home")}/Library/Application Support/PrismLauncher" +
        "/libraries/com/mojang/minecraft/$mcVersion/minecraft-$mcVersion-client.jar"
    val jarPath = (project.findProperty("clientJar") as String?) ?: defaultJar
    val indexFile = displayKitRootDir.resolve("core/src/main/resources/displaykit/sprites.json")
    val outDir = project.file("src/main/resources/displaykit/slices")

    mainClass.set("io.schemat.displaykit.pack.gen.SpriteSliceGenerator")
    classpath = sourceSets["main"].runtimeClasspath
    args(jarPath, indexFile.absolutePath, outDir.absolutePath)
}
