// Llamatik as an Agent Client Protocol (ACP) agent: any ACP editor (Zed, JetBrains, Neovim, ...) can
// launch it over stdio and use the same AgentLoop as the IDE plugin and the CLI. No com.intellij.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":agent-core"))
    implementation(project(":backends"))
    // Pre-1.0 and released often: pinned, and only this module depends on it.
    implementation(libs.acp)
    runtimeOnly(libs.gson)
    // The SDK logs through kotlin-logging/SLF4J and ships no backend. slf4j-simple writes to stderr, which
    // matters: stdout carries the protocol.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":agent-core")))
    testImplementation(testFixtures(project(":backends")))
    testImplementation(libs.gson)
}

application {
    mainClass.set("com.llamatik.acp.MainKt")
    applicationName = "llamatik-acp"
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
