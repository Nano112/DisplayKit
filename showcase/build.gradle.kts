plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

group = "io.schemat.displaykit"
version = providers.gradleProperty("displaykitVersion").orElse("0.2.0").get()

val fabricProjectPath = if (rootProject.findProject(":libs:displaykit:fabric") != null) {
    ":libs:displaykit:fabric"
} else {
    ":fabric"
}
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
    archivesName.set("DisplayKit-Showcase-mc${libs.versions.minecraft.get()}")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.language.kotlin)

    // namedElements gives dev (named-namespace) jars from Loom subprojects
    implementation(project(path = fabricProjectPath, configuration = "namedElements"))
    implementation(project(coreProjectPath))
    implementation(project(packProjectPath))
}

loom {
    runs {
        named("client") {
            client()
            configName = "Showcase Client"
            ideConfigGenerated(true)
            runDir("run")
        }
        named("server") {
            server()
            configName = "Showcase Server"
            ideConfigGenerated(true)
            runDir("run")
            // DisplayKit's pack HTTP server defaults to 8080, which commonly
            // collides with Docker and other local services. A collision is
            // quiet but fatal here: the pack never serves, so every sprite
            // glyph renders as a missing-glyph box.
            vmArg("-Ddisplaykit.pack.port=8099")
            vmArg("-Ddisplaykit.debug.tabRuler=" + (findProperty("dk.tabRuler") ?: "false"))
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
