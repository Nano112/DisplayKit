plugins {
    id("fabric-loom") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

// This file is also the root build for the standalone DisplayKit repository.
// The guard keeps an embedding build's grouping project from reconfiguring
// unrelated projects when these same sources are consumed as subprojects.
if (rootProject == project) {
    allprojects {
        repositories {
            mavenLocal()
            mavenCentral()
            maven("https://maven.fabricmc.net/") {
                name = "Fabric"
            }
        }

        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
                jvmToolchain(21)
            }
        }
    }
}
