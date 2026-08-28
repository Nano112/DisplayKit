plugins {
    alias(libs.plugins.fabric.loom)
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
val packProjectPath = if (rootProject.findProject(":libs:displaykit:pack") != null) {
    ":libs:displaykit:pack"
} else {
    ":pack"
}

base {
    archivesName.set("DisplayKit-Fabric-mc${libs.versions.minecraft.get()}")
}

dependencies {
    // Both modules occur throughout the public Fabric integration API. They
    // remain included in the distributable mod jar for one-file deployment.
    api(project(coreProjectPath))
    api(project(packProjectPath))

    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.language.kotlin)

    compileOnly(libs.joml)

    include(project(coreProjectPath))
    include(project(packProjectPath))
    include(libs.joml)

    // This module had no test source set at all, which is why its defects
    // reached the client instead of a build: the surface ticker's grab/hover
    // path and the pack-sync ritual both shipped broken and were caught by
    // eye, in-world. Anything here that does NOT need a live Minecraft server
    // -- accounting, wrap arithmetic, packet field math -- is testable, and
    // should be tested.
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

loom {
    runs {
        named("client") {
            client()
            configName = "DisplayKit Client"
            ideConfigGenerated(true)
            runDir("run")
        }
        named("server") {
            server()
            configName = "DisplayKit Server"
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", libs.versions.minecraft.get())
    inputs.property("loader_version", libs.versions.fabric.loader.get())

    from(displayKitRootDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_displaykit" }
    }

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get(),
            "loader_version" to libs.versions.fabric.loader.get()
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.named<Jar>("sourcesJar") {
    from(displayKitRootDir.resolve("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_displaykit" }
    }
}

publishing {
    publications {
        create<MavenPublication>("displayKitFabric") {
            artifact(tasks.named("remapJar"))
            artifact(tasks.named("remapSourcesJar"))
            artifactId = "displaykit-fabric-mc${libs.versions.minecraft.get()}"
            pom {
                name.set("DisplayKit Fabric")
                description.set("Fabric runtime, packet renderers, input routing, and lifecycle integration for DisplayKit")
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
                    dependency("io.schemat.displaykit", "displaykit-pack", project.version.toString())
                    dependency("net.fabricmc", "fabric-loader", libs.versions.fabric.loader.get())
                    dependency("net.fabricmc.fabric-api", "fabric-api", libs.versions.fabric.api.get())
                    dependency(
                        "net.fabricmc",
                        "fabric-language-kotlin",
                        libs.versions.fabric.language.kotlin.get(),
                    )
                }
            }
        }
    }
}
