// Headless agent library. Must stay free of com.intellij (and of the plugin's licensing, settings,
// analytics and persistence code): the CLI, the ACP adapter and the benchmarks depend on it too.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
