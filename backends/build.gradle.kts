// Headless inference backends and the Llamatik server client. No com.intellij: shared by the IDE
// plugin, the CLI and the ACP agent.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}


dependencies {
    api(project(":agent-core"))
    // Native llama.cpp bridge (local inference).
    api(libs.llamatik.core)
    // Server discovery (mDNS). Everything else is plain JDK: the plugin runs on the IDE's own coroutines, ktor
    // and serialization, and the Llamatik network library (ktor 3.6, coroutines 1.11) cannot run on those.
    implementation(libs.jmdns)
    // Gson comes with the IDE platform, so it must not be bundled into the plugin: compile-only here, and
    // added explicitly by the command-line applications.
    compileOnly(libs.gson)

    testFixturesApi(project(":agent-core"))
    testFixturesCompileOnly(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}

tasks.test {
    // Real-model tests load the native library.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
