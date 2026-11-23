import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.nexus.publish)
}

// Same coordinates as the Llamatik inference library: the group is verified once for everything under it.
group = "com.llamatik"
version = System.getenv("RELEASE_VERSION") ?: (findProperty("releaseVersion") as String?) ?: "0.0.0-SNAPSHOT"

// Release flow (see .github/workflows/publish-mavencentral.yml):
//   ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set((findProperty("mavenCentralUsername") as String?) ?: System.getenv("MAVEN_CENTRAL_USERNAME"))
            password.set((findProperty("mavenCentralPassword") as String?) ?: System.getenv("MAVEN_CENTRAL_PASSWORD"))
            packageGroup.set("com.llamatik")
        }
    }
}

// Maven coordinates of the published libraries: project name -> artifactId + POM description.
// The applications (:cli, :acp) are intentionally not here.
val libraries = mapOf(
    "agent-core" to Pair(
        "agent-core",
        "Headless coding-agent runtime for Llamatik: the agent loop, prompts, tools and host abstractions. No IDE dependency.",
    ),
    "backends" to Pair(
        "agent-backends",
        "Inference backends for the Llamatik coding agent: the local llama.cpp engine, the Llamatik server client " +
            "(resumable streaming) and network discovery. Plain JDK code.",
    ),
)

configure(subprojects.filter { it.name in libraries }) {
    val (artifact, description) = libraries.getValue(name)
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // The Java plugin comes with the module's Kotlin plugin, which is applied after this block runs, so
    // everything that needs the "java" component is configured once it is there.
    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        // Maven Central requires a -javadoc.jar on every publication. Kotlin-only modules produce none, so attach
        // an empty one (Central only checks that it exists).
        val javadocJar = tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    artifactId = artifact
                    from(components["java"])
                    artifact(javadocJar)
                    pom {
                        name.set("Llamatik Coding Agent: $artifact")
                        this.description.set(description)
                        url.set("https://github.com/llamatik/Llamatik-Coding-Agent")
                        licenses {
                            license {
                                name.set("MIT")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        developers {
                            developer {
                                id.set("ferranpons")
                                name.set("Ferran Pons")
                                url.set("https://github.com/ferranpons")
                            }
                        }
                        scm {
                            url.set("https://github.com/llamatik/Llamatik-Coding-Agent")
                            connection.set("scm:git:git://github.com/llamatik/Llamatik-Coding-Agent.git")
                            developerConnection.set("scm:git:ssh://github.com/llamatik/Llamatik-Coding-Agent.git")
                        }
                    }
                }
            }
        }

        // Unsigned local builds (publishToMavenLocal) work without a key; releases provide SIGNING_KEY.
        extensions.configure<SigningExtension> {
            val signingKey = (findProperty("signingInMemoryKey") as String?) ?: System.getenv("SIGNING_KEY")
            val signingPassword = (findProperty("signingInMemoryKeyPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }

        tasks.withType<AbstractPublishToMaven>().configureEach {
            dependsOn(tasks.withType<Sign>())
        }
    }
}
