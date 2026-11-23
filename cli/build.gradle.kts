// Terminal front-end of the Llamatik agent ("Claude Code for Llamatik"). Runs the same AgentLoop as
// the IDE plugin on a local model or a Llamatik server. No com.intellij.
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

    // JmDNS (server discovery) logs through SLF4J; silence the "no provider" notice.
    runtimeOnly(libs.gson)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":agent-core")))
    testImplementation(testFixtures(project(":backends")))
    testImplementation(libs.gson)
}

application {
    mainClass.set("com.llamatik.cli.MainKt")
    applicationName = "llamatik"
}

tasks.test {
    // The native library is only needed for --model runs; tests never load a model.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
