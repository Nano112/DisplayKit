import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "io.schemat.displaykit"
version = providers.gradleProperty("displaykitVersion").orElse("0.1.0").get()

val displayKitRootDir = rootProject.file("libs/displaykit")
    .takeIf { it.resolve("LICENSE").isFile }
    ?: rootProject.projectDir
val coreProjectPath = if (rootProject.findProject(":libs:displaykit:core") != null) {
    ":libs:displaykit:core"
} else {
    ":core"
}
val packProjectPath = if (rootProject.findProject(":libs:displaykit:pack") != null) {
    ":libs:displaykit:pack"
} else {
    ":pack"
}

base {
    archivesName.set("displaykit-velocity")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    // Module-local: the root build only configures repositories when this
    // checkout is the root project, and neither host repo is needed by the
    // other modules.
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://repo.codemc.io/repository/maven-releases/") {
        name = "CodeMC"
    }
}

dependencies {
    api(project(coreProjectPath))
    api(project(packProjectPath))

    // Both provided by the consumer: the module runs inside a Velocity plugin
    // that already ships PacketEvents. Compiling against the vanilla
    // coordinates keeps the module consumer-agnostic; a consumer that
    // relocates PacketEvents when shading rewrites these references too.
    //
    // Velocity 4 publishes metadata declaring Java 25, while this module
    // emits Java 21 bytecode like the rest of the repo. Compiling 21 against
    // the 25 API is fine for a compileOnly dependency, every real host runs
    // the proxy's own Java 25 anyway, so the variant check is relaxed for it.
    compileOnly(libs.velocity.api) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
    compileOnly(libs.packetevents.velocity)

    testImplementation(kotlin("test"))
    // The encoder golden tests build PacketEvents EntityData values directly.
    testImplementation(libs.packetevents.api)
    // The item registry allocates real byte buffers at class-init, so tests
    // need the netty implementation and netty itself on the classpath.
    testImplementation("com.github.retrooper:packetevents-netty-common:${libs.versions.packetevents.get()}")
    testImplementation("io.netty:netty-buffer:4.1.115.Final")
    testImplementation("net.kyori:adventure-nbt:5.2.0")
    testImplementation(libs.velocity.api) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("displayKitVelocity") {
            // Same Gradle 9 publication-bridge workaround as the pack module:
            // publish the concrete artifacts and spell out the runtime graph.
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar"))
            artifactId = "displaykit-velocity"
            pom {
                name.set("DisplayKit Velocity")
                description.set("Velocity proxy platform for DisplayKit, packet-only via PacketEvents")
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
                    fun dependency(group: String, artifact: String, dependencyVersion: String, scope: String) {
                        val node = dependencies.appendNode("dependency")
                        node.appendNode("groupId", group)
                        node.appendNode("artifactId", artifact)
                        node.appendNode("version", dependencyVersion)
                        node.appendNode("scope", scope)
                    }
                    dependency("io.schemat.displaykit", "displaykit-core", project.version.toString(), "compile")
                    dependency("io.schemat.displaykit", "displaykit-pack", project.version.toString(), "compile")
                    dependency("org.jetbrains.kotlin", "kotlin-stdlib", libs.versions.kotlin.get(), "compile")
                    dependency("com.velocitypowered", "velocity-api", libs.versions.velocity.api.get(), "provided")
                    dependency("com.github.retrooper", "packetevents-velocity", libs.versions.packetevents.get(), "provided")
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
}

tasks.named<Jar>("sourcesJar") {
    from(displayKitRootDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_displaykit" }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
