rootProject.name = "llamatik-coding-agent"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Libraries (published to Maven Central): agent-core, backends.
include(":agent-core")
include(":backends")
// Applications (distributed as archives, not published as libraries): cli, acp.
include(":cli")
include(":acp")
