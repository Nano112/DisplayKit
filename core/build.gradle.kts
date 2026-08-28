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

base {
    archivesName.set("displaykit-core")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Both types occur in public model/geometry signatures.
    api(libs.joml)
    api("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
}

java {
    withSourcesJar()
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

publishing {
    publications {
        create<MavenPublication>("displayKitCore") {
            from(components["java"])
            artifactId = "displaykit-core"
            pom {
                name.set("DisplayKit Core")
                description.set("Retained, server-driven Minecraft UI models, layout, surfaces, and world layers")
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
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
