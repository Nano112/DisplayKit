plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

group = "io.schemat.displaykit"
version = "0.1.0"

base {
    archivesName.set("DisplayKit-Fabric-mc${libs.versions.minecraft.get()}")
}

dependencies {
    implementation(project(":libs:displaykit:core"))
    implementation(project(":libs:displaykit:pack"))

    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.language.kotlin)

    compileOnly(libs.joml)

    include(project(":libs:displaykit:core"))
    include(project(":libs:displaykit:pack"))
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
}
