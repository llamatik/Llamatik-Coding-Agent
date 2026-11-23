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

    // Line editing, history and completion for the interactive prompt.
    implementation(libs.jline)
    // Styled output: tables and progress bars.
    implementation(libs.mordant)

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

// llama.cpp / ggml write their start-up chatter (Metal kernel compilation and the like) straight to file
// descriptor 2, which the JVM cannot intercept. The launcher therefore points fd 2 at /dev/null and hands the
// program a copy of the real stderr as fd 3 (LLAMATIK_ERR_FD), which Main uses for its own messages.
// `--native-logs` keeps everything on stderr.
tasks.startScripts {
    doLast {
        val exec = "exec \"\$JAVACMD\" \"\$@\""
        val quiet = """
            |native_logs=0
            |for arg in "${'$'}@"; do
            |    [ "${'$'}arg" = "--native-logs" ] && native_logs=1
            |done
            |if [ "${'$'}native_logs" = 0 ] && [ -z "${'$'}LLAMATIK_NATIVE_LOGS" ]; then
            |    LLAMATIK_ERR_FD=3
            |    export LLAMATIK_ERR_FD
            |    exec "${'$'}JAVACMD" "${'$'}@" 3>&2 2>/dev/null
            |fi
            |$exec
        """.trimMargin()
        unixScript.writeText(unixScript.readText().replace(exec, quiet))
    }
}
