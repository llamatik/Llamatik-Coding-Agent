<p align="center">
  <img src="assets/llamatik-new-logo.png" alt="Llamatik Logo" width="150"/>
</p>

<h1 align="center">Llamatik Coding Agent</h1>

<p align="center">
  <b>A coding agent that runs on local models and Llamatik servers — as libraries, a terminal app and an editor agent.</b>
</p>

<p align="center">
  Local by default · Privacy-preserving · Plain JDK, no IDE dependency
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.llamatik/agent-core"><img src="https://img.shields.io/maven-central/v/com.llamatik/agent-core.svg" alt="maven central badge"/></a>
  <img src="https://img.shields.io/badge/Kotlin-JVM%2021-blueviolet" alt="kotlin badge"/>
  <img src="https://img.shields.io/badge/Inference-llama.cpp-orange" alt="llama.cpp badge"/>
  <img src="https://img.shields.io/badge/Models-GGUF-green" alt="gguf badge"/>
  <img src="https://img.shields.io/badge/Protocol-ACP-blue" alt="acp badge"/>
  <img src="https://img.shields.io/badge/License-MIT-lightgrey" alt="license badge"/>
  <a href="https://plugins.jetbrains.com/plugin/31304"><img src="https://img.shields.io/jetbrains/plugin/v/31304" alt="jetbrains plugin badge"/></a>
</p>

---

## ✨ What is the Llamatik Coding Agent?

The agent behind [Llamatik Code](https://plugins.jetbrains.com/plugin/31304), extracted so it can run anywhere: in your terminal, in any editor that speaks the Agent Client Protocol, or embedded in your own app.

- 🤖 **Agent loop** — prompts, tool calling, proposal validation and host abstractions in `agent-core`
- 🔒 **Privacy-first** — runs a local GGUF model in-process, or talks to a [Llamatik Server](https://github.com/ferranpons/Llamatik-Server)
- 🖥 **Terminal agent** — `llamatik`, with diff previews and approval before every edit or command
- 🔌 **ACP agent** — `llamatik-acp` for Zed, JetBrains, Neovim and other ACP clients
- 📡 **Network discovery** — find Llamatik servers on your LAN, with resumable streaming

---

## 🧱 Modules

| Module | What it is | Distributed as |
|---|---|---|
| `agent-core` | The agent: loop, prompts, tools, host abstractions. No IDE dependency. | `com.llamatik:agent-core` |
| `backends` | Local llama.cpp engine, Llamatik server client (resumable streaming), network discovery. Plain JDK code. | `com.llamatik:agent-backends` |
| `cli` | `llamatik`: terminal agent. | distribution archive |
| `acp` | `llamatik-acp`: Agent Client Protocol agent over stdio (Zed, JetBrains, Neovim, ...). | distribution archive |

`agent-core` and `backends` must stay free of `com.intellij` and of coroutines/ktor/serialization (they run inside
IDE plugins whose classloaders ship older copies of those). See [docs/agent-core-extraction.md](docs/agent-core-extraction.md) for the design and [docs/command-line-and-acp.md](docs/command-line-and-acp.md) for the CLI and ACP guide.

---

## 🔧 Build

```bash
./gradlew build            # compile and test everything
./gradlew :cli:installDist # build/install/cli/bin/llamatik
./gradlew :acp:installDist # build/install/acp/bin/llamatik-acp
```

---

## ⚡ Quick Start

```bash
# Local GGUF model, in-process
build/install/cli/bin/llamatik --model /path/to/model.gguf

# A Llamatik server (e.g. the Llamatik app with "Share this device")
build/install/cli/bin/llamatik --server 192.168.1.20

# Scan the local network and pick a server
build/install/cli/bin/llamatik --discover

# One-shot prompt
build/install/cli/bin/llamatik -m model.gguf -p "Explain this project"
```

For editors, point your ACP client at `build/install/acp/bin/llamatik-acp` (same `--model` / `--server` options).

---

## 📦 Use the libraries

```kotlin
dependencies {
    implementation("com.llamatik:agent-core:<version>")
    implementation("com.llamatik:agent-backends:<version>")
}
```

Try an unreleased build locally with `./gradlew publishToMavenLocal -PreleaseVersion=0.1.0-local` and add
`mavenLocal()` to the consumer.

---

## 🚀 Release

Publishing a GitHub release tagged `vX.Y.Z` runs `.github/workflows/publish-mavencentral.yml`: it tests, signs and
publishes `agent-core` and `agent-backends` as `X.Y.Z` to Maven Central (same `com.llamatik` namespace and
secrets as the Llamatik library: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`,
`SIGNING_PASSWORD`). Locally: `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository` with the same
values as environment variables and `RELEASE_VERSION` set.

---

## 📜 License

This project is released under the [MIT License](./LICENSE). The **Llamatik name and logo are proprietary marks** owned by Ferran Pons and are not covered by the MIT License.

---

Built with ❤️ for the Kotlin community.
