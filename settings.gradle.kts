pluginManagement {
    plugins {
        id("fabric-loom") version "1.14-SNAPSHOT"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
    }
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "displaykit"

include(":core")
include(":pack")
include(":fabric")
include(":showcase")
