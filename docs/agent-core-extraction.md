# Extracting `agent-core` from Llamatik Code

Status: implemented, phases 0–6 (see "Outcome" at the end)
Repo: `Llamatik Teammate` (this repo). The extracted module may move to its own repo later.

## 1. Goal

Pull the agent out of the IntelliJ plugin into a headless JVM library, `agent-core`, so the same agent can power:

1. the IntelliJ plugin (Llamatik Code), with **no change in user-visible behavior**
2. a terminal CLI (a "Claude Code for Llamatik"; also the base for an Omarchy integration)
3. an ACP (Agent Client Protocol) adapter, so any ACP editor can launch it over stdio
4. the benchmark harness, which today uses a separate copy of the loop

Non-goals for this refactor:

- No new agent features, prompt changes or model-behavior changes.
- No change to licensing/trial/Marketplace code. It stays in the plugin and must never enter `agent-core`.
- No publishing to Maven yet. `agent-core` is a Gradle subproject for now. Keep its API publish-ready.

## 2. Current state (findings)

Measured on the current `main`:

- 187 main Kotlin files; **150 import `com.intellij`**.
- `agent/AgentOrchestrator.kt` is 1771 lines, an IntelliJ `@Service(Level.PROJECT)` built around a `Project`. It reaches collaborators through the IntelliJ service locator (`ToolRegistry`, `ProjectContextService`, `SkillService`, `CodebaseRulesService`, `ProjectMemoryService`, `LlamatikRouter`, `MultimodalBackendService`, `LlamatikAppSettings`).
- It mixes several concerns in one class:
  - the round loop (`submit`, `runRound` ~L328–795, `chat` ~L1196)
  - streaming token filtering (`filteredToken`, `holdLenForTags`, `flushAnswerBuffer`)
  - prompt construction (`buildSystemPrompt`, `buildSecondPassSystemPrompt`, `buildContextHint`, `fitMessagesInBudget`, enterprise-mission additions)
  - file/build-error reference resolution (`resolveFileReferences`, `resolveCurrentFileReference`, `resolveBuildErrorReferences`)
  - edit-proposal extraction (`extractEditProposals`, `buildProposal`, `buildProposalsFromToolMessages`)
  - multimodal submit (`submitMultimodal`)
- The tool contract is IntelliJ-bound: `ToolExecutionContext(val project: Project)`, and all 38 files in `tools/impl` take a `Project` (`BaseProjectTool` builds a `ProjectContextService(project)`).
- `ProjectContextService` (204 lines) does file listing, reading, search, and ignore/safety checks through `VirtualFile`, `FileEditorManager`, and so on. It is the real file-system abstraction that needs a headless twin.

Already reusable (no or almost no IntelliJ imports):

- `backend/AssistantBackend.kt` (`AssistantBackend`, `GenerationRequest`, `GenerationCallbacks`, `GenerationHandle`, `BackendConfig`, `SessionTag`)
- `tools/ToolCallParser.kt` (311 lines) plus `ToolCall` / `ToolResult` (the data classes only)
- `agent/RoundDirective.kt`, `agent/AgentTrace.kt`, `agent/ProposalValidator.kt`, `agent/AgentFallbackPolicy.kt`
- `model/ChatModels.kt`
- `mcp/McpClientService.kt` (543 lines, 2 IntelliJ imports), `mcp/McpAgentTools.kt`
- `src/test/.../benchmark/HeadlessAgent.kt` (281 lines): a test-only copy of the loop that already runs on a plain `File` root. It documents that it "mirrors `AgentOrchestrator.runRound`". Two copies of the loop will drift; the goal is one.

Other observations:

- `server/LlamatikServerClient.kt` (413 lines) in this repo duplicates the `network` module of `llama-kmp` (`com.llamatik:network`, newly publishable). Decide separately whether to replace it. It is out of scope here, but `agent-core` should depend only on the `AssistantBackend` interface so that either implementation works.
- The root project has odd `commonMain/` and `jvmMain/` directories next to `src/`. Check whether they are used before adding modules.

## 3. Target architecture

```
settings.gradle.kts
  include(":agent-core")        # new: plain Kotlin/JVM, NO com.intellij dependency
  (root project)                # the IntelliJ plugin, depends on :agent-core
  # later: :cli, :acp (siblings, depend on :agent-core only)
```

Hard rule, enforced by the build: `agent-core` has no `com.intellij` dependency. If one slips in, compilation fails. Do **not** add the intellij platform plugin to this module.

### 3.1 What moves into `agent-core` (package `com.llamatik.agent.core.*`)

| Area | Source today | Notes |
|---|---|---|
| Backend contracts | `backend/AssistantBackend.kt` | move as is |
| Chat model | `model/ChatModels.kt` (+ whatever `EditProposal` / `AttachedImage` need) | split out of `DomainModels.kt` if mixed |
| Tool contract | `tools/ToolInterfaces.kt`, `ToolCallParser.kt` | change `ToolExecutionContext`, see 3.2 |
| Round loop + directives | `AgentOrchestrator.runRound/submit/chat`, `RoundDirective`, `AgentTrace`, `AgentFallbackPolicy`, `ProposalValidator` | the main extraction |
| Stream filtering | `filteredToken`, `holdLen*`, `flushAnswerBuffer` | pure string logic; extract into its own class with tests |
| Prompt building | `buildSystemPrompt`, `fitMessagesInBudget`, etc. | take inputs through interfaces, not services |
| Proposal extraction | `extractEditProposals`, `buildProposal*` | pure logic |
| Portable tools | file read/list/search, write/create/replace/apply-patch, git status/diff/branch, shell | re-implemented on the abstractions below |
| MCP client | `McpClientService` and models | strip the 2 IntelliJ imports |

### 3.2 Abstractions that replace `Project`

Define these in `agent-core`; the plugin and the CLI each provide implementations.

```kotlin
interface WorkspaceFiles {            // replaces ProjectContextService's file part
    val rootPath: String
    fun list(relativePath: String?): List<String>
    fun read(path: String): String
    fun write(path: String, content: String)
    fun searchFilenames(query: String): List<String>
    fun searchCode(query: String): List<String>
    fun isIgnored(path: String): Boolean
}
interface ShellRunner { fun run(command: String, cwd: String, timeoutMs: Long): ShellResult }
interface GitRunner   { /* status, diff, changedFiles, branch: may be built on ShellRunner */ }
interface PermissionPrompt { fun confirm(request: PermissionRequest): Decision }  // edits, shell, MCP
interface EditorContext {             // optional; null in the CLI
    fun currentFile(): String?
    fun selectedText(): String?
}
interface AgentEnvironment {          // passed to every tool and to the loop
    val files: WorkspaceFiles
    val shell: ShellRunner
    val git: GitRunner
    val permissions: PermissionPrompt
    val editor: EditorContext?
    val rules: RulesProvider          // CodebaseRulesService equivalent
    val memory: MemoryProvider        // ProjectMemoryService equivalent
    val skills: SkillProvider         // SkillService equivalent
}
// ToolExecutionContext(val project: Project)  ->  ToolExecutionContext(val env: AgentEnvironment)
```

Keep interfaces small. If a provider (rules, memory, skills) is only a thin read of files, prefer a default implementation in `agent-core` over an interface.

### 3.3 What stays in the plugin

- `@Service` wrappers: `AgentOrchestrator` becomes a thin IntelliJ service that builds an `AgentEnvironment` from the `Project` and delegates to the core loop. Its public signatures (`submit`, `chat`, `selectedModelLabel`, `isModelLoaded`, …) stay unchanged so UI code does not change.
- IDE-only tools: PSI (`PsiTools`), index, run-config, diagnostics, build-failure, code health, KMP native diagnostics, `GetCurrentFileTool`/`GetSelectedTextTool` (via `EditorContext`), `ShowDiffTool` and anything using `DiffPreviewService`. They implement the same `LlamatikTool` interface and are registered in the plugin's `ToolRegistry`.
- `LlamatikRouter`, `LlamatikBackendService`, `MultimodalBackendService`, settings, persistence, UI, licensing/trial/analytics, code-health, completion.
- IntelliJ implementations of the abstractions: `IdeWorkspaceFiles` (wraps today's `ProjectContextService`), `IdeShellRunner` (wraps `TerminalService`), and a permission prompt using today's dialogs.

## 4. Phases

Each phase ends green (`./gradlew build test`) and with the plugin behaving as before. Do them in order, one commit per phase (only when the user asks to commit).

### Phase 0: Baseline (no code changes)

- Run the full test suite and record the result (counts, failures). Record any tests that already fail so they are not blamed on the refactor.
- Check whether the root `commonMain/` and `jvmMain/` directories are used by the build.
- Read `AgentLoopTest`, `HeadlessAgent`, `PromptBudgetTest`, `RoundDirectiveTest`, `AgentFallbackPolicyTest`, `AgentTraceTest`, `ProposalValidatorTest`, `CurrentFileResolverTest` to learn the behavior that is pinned.
- Write the baseline results into the PR/summary.

### Phase 1: Create the module and move the pure code

- Add `agent-core` (Kotlin/JVM, toolchain 21, JUnit 4 like the root). Add `include(":agent-core")` and `implementation(project(":agent-core"))` in the root.
- Dependencies: Kotlin stdlib and what is strictly needed (check what `McpClientService` and `ToolCallParser` use). Be careful with kotlinx.coroutines and the Kotlin stdlib: the IntelliJ platform ships its own, so use `compileOnly` or exclude as the IntelliJ Platform Gradle Plugin recommends, and verify the packaged plugin zip does not contain duplicate or conflicting jars.
- Move, with packages renamed and the plugin's imports updated: backend contracts, `ChatModels`, `ToolCall`/`ToolResult`/`ToolCallParser`, `RoundDirective`, `AgentTrace`, `AgentFallbackPolicy`, `ProposalValidator`, and their tests.
- Acceptance: tests moved with the code still pass, plugin compiles, zero `com.intellij` in `agent-core`.

### Phase 2: Tool contract and environment abstractions

- Introduce `AgentEnvironment` and the interfaces from 3.2. Change `ToolExecutionContext` to carry `env`.
- Implement the IntelliJ side (`IdeWorkspaceFiles`, `IdeShellRunner`, permission prompt, `EditorContext`) as adapters over the existing services. No logic duplication: adapters delegate.
- Port the portable tools to `agent-core` on top of the interfaces (list/read/search/search_filenames, write/create/replace/apply_patch, git tools, shell if one exists). Keep **tool names, argument names and result text byte-identical**, because models were prompted and benchmarked against them.
- IDE-only tools remain in the plugin and get the environment through `ToolExecutionContext`.
- Acceptance: the plugin's `ToolRegistry` registers both sets; existing tool tests pass; add tests for the portable tools using a temp-dir `WorkspaceFiles`.

### Phase 3: Extract the loop (the big one)

Split `AgentOrchestrator` into three layers:

1. **`AgentLoop` (core)**: `submit/runRound/chat` logic, written against `AssistantBackend`, `AgentEnvironment`, callbacks. No `Project`, no `service<>()`, no UI types.
2. **`StreamFilter` (core)**: the tag-hold and token filtering logic (`filteredToken`, `holdLen*`, `flushAnswerBuffer`) as a standalone, unit-tested class. Note that `filteredToken` is currently duplicated between `submit` and `chat`; keep behavior identical and do not "fix" differences silently. If the two versions differ, document it and keep both behaviors behind a parameter.
3. **`AgentOrchestrator` (plugin)**: thin `@Service(PROJECT)` that builds the environment, reads settings, and delegates. The public API is unchanged.

Prompt construction (`buildSystemPrompt` and friends) moves with inputs supplied through a small `PromptInputs` data class or the providers, so the exact strings produced are unchanged. Add **golden tests**: render prompts from fixed inputs with the old code first (before the move), save the output, and assert equality after the move.

Multimodal (`submitMultimodal`) depends on `MultimodalBackendService`. Keep it in the plugin for now unless it separates cleanly. It is not needed for the CLI.

- Acceptance: `AgentLoopTest` and the other agent tests pass against the real core loop; golden prompt tests pass; manual smoke test of the plugin: chat, an edit proposal, a tool call, cancel.

### Phase 4: One loop for everything

- Delete the test-only copy of the loop. Rewrite `HeadlessAgent` (benchmarks) as a thin wrapper that runs the real `AgentLoop` with a temp-dir `AgentEnvironment` and a scripted/real backend.
- `AgentLoopTest.AgentLoop` (the mirrored test copy) should also use the real loop.
- Acceptance: benchmark harness tests pass; no remaining second implementation of the round loop.

### Phase 5: CLI (separate task, after 1–4 are merged)

- New sibling module `cli` that depends on `agent-core` and `com.llamatik:core` (or on the Llamatik server through `AssistantBackend`).
- Implements `WorkspaceFiles` / `ShellRunner` / `GitRunner` on `java.nio`/`ProcessBuilder`, and a terminal `PermissionPrompt` (y/N/always).
- Minimal v1: interactive REPL, streamed output, read/edit/shell tools with confirmation, `--model`, `--server <url>`, one-shot `-p "prompt"`. No context compaction, no sandbox, no session resume yet.

### Phase 6: ACP adapter (separate task)

- New sibling module `acp`: JSON-RPC over stdio using the ACP Kotlin SDK (check its maturity and license first). Map `session/prompt` to `AgentLoop`, tokens to `session/update`, edits and shell through the client's file-system, terminal and permission methods (these map to `WorkspaceFiles`, `ShellRunner` and `PermissionPrompt`).

## 5. Test strategy

- Never lower coverage: every moved class keeps its tests, moved along with it.
- Add golden tests for prompt rendering (before moving the code) and for tool output text.
- Add `StreamFilter` tests covering split tags across token boundaries.
- Smoke-test the plugin manually after phases 2 and 3 (`./gradlew runIde`): chat, agent edit with a proposal, tool calls, cancellation, a remote-server backend, MCP.
- After phase 4, run the benchmark harness on one small model to compare pass rate and rounds before and after. A regression here means the prompts or tool output changed.

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Behavior drift in prompts or tool output, which small local models are sensitive to | golden tests; byte-identical tool names/args/results; benchmark before/after |
| The orchestrator is large and entangled (UI callbacks, settings, multimodal) | extract in small steps and keep the public API; leave multimodal in the plugin initially |
| Stdlib/coroutines duplicate-jar or classloader conflicts in the plugin zip | follow the IntelliJ Platform Gradle Plugin guidance for Kotlin stdlib; inspect the built zip |
| `filteredToken` duplicated between `submit` and `chat` | extract one `StreamFilter`; if the two differ, preserve both explicitly |
| Licensing/trial code leaking into the shared module | keep it out; `agent-core` has no dependency on settings, licensing, analytics or persistence |
| Scope creep into a full Claude-Code clone | CLI v1 stays minimal (see Phase 5) |

## 7. Definition of done (phases 1–4)

- `agent-core` builds with no `com.intellij` dependency.
- Exactly one implementation of the round loop exists.
- The plugin's tests pass, golden prompt tests pass, and the plugin smoke test is unchanged.
- `AgentOrchestrator` is a thin wrapper (target: under ~300 lines).
- Public plugin APIs used by the UI are unchanged.

---

## 8. Prompt to give Claude

Paste everything inside the block into a Claude Code session started in this repo's root.

````text
You are working in the Llamatik Code IntelliJ plugin repo (this directory). Read
docs/agent-core-extraction.md completely before doing anything. It is the plan
and the source of truth for this task.

Goal: extract the agent into a headless Gradle subproject `agent-core` with no
com.intellij dependency, keeping the plugin's behavior identical. Execute
Phases 0 to 4 of the plan, in order. Phases 5 (CLI) and 6 (ACP) are NOT part of
this task.

Rules:
1. Behavior must not change. Tool names, argument names, tool result text and
   prompt strings must stay byte-identical. Before moving any prompt-building
   code, write golden tests that capture its current output from fixed inputs,
   then keep them passing after the move.
2. Work one phase at a time. At the end of each phase, run
   `./gradlew build test` (or the closest equivalent that works here), report the
   results, and STOP and wait for my confirmation before starting the next phase.
   Do not commit or push unless I ask.
3. Phase 0 is read-only: run the tests, record the baseline (including tests that
   already fail), check whether the root `commonMain/` and `jvmMain/` dirs are
   used by the build, read the agent tests listed in the plan, and report. Then
   stop.
4. `agent-core` must never depend on com.intellij, the licensing/trial code,
   settings, analytics or persistence. Do not apply the intellij platform Gradle
   plugin to it. Be careful with Kotlin stdlib/coroutines packaging: after the
   module exists, inspect the built plugin zip for duplicate or conflicting jars.
5. Keep the public API of the plugin's `AgentOrchestrator` unchanged so the UI
   code does not need to change. It becomes a thin wrapper around the core loop.
6. Do not "fix" bugs or inconsistencies you notice while moving code (for
   example differences between the two `filteredToken` implementations in
   `submit` and `chat`). Preserve behavior, and list what you found in your
   report so I can decide.
7. Move tests together with the code they cover. Never delete or weaken a test
   to make the build pass. If a test must change, say why.
8. If something in the plan does not match the code (a file moved, an assumption
   is wrong), stop and tell me instead of improvising a different design.
9. Keep diffs reviewable: prefer moving files with their history (git mv) and
   small, mechanical changes in each step. Separate "move" from "change".

Start with Phase 0 now. When it is done, summarize the baseline and any
differences between the plan and the code, then wait.
````


---

## 9. Outcome

All phases are done.

| Module | What it is |
|---|---|
| `agent-core` | The agent: loop, prompts, tools, host abstractions. No IntelliJ. Golden transcripts pin the prompts. |
| `backends` | The one local inference engine (`LocalLlamaBackend`, also behind the plugin's `LlamatikBackendService`), the Llamatik server client with resumable streaming (`remote/`), pairing and network discovery. Plain JDK code: no coroutines, ktor or serialization. Shared by plugin, CLI and ACP. |
| `cli` | `llamatik`: terminal agent. |
| `acp` | `llamatik-acp`: Agent Client Protocol agent over stdio (SDK `com.agentclientprotocol:acp:0.34.0`, pinned). |

Differences from the plan, and why:

* `:backends` is a module of its own (the plan had CLI and ACP depend on `agent-core` only). The server client,
  its resume logic and the local native backend are needed by three hosts and used to live inside the plugin.
* The host abstractions package is `core.host` (not `env`).
* `ShowDiffTool` moved to core; `ConfirmingToolExecutor` is in core so CLI and ACP share it.
* The ACP SDK's README is out of date for 0.34 (`Agent(protocol, support)` takes no extension list; sessions are
  created with `SessionCreationParameters`; client operations come from `currentCoroutineContext().client`). Only
  `:acp` depends on it.
* Defects found along the way and fixed (all pre-existing): questions forced into a write pass, an unbounded
  re-escalation loop, `noThink` not applied on every round, tool-call XML accepted as file content, command-only
  tasks losing their answer, trailing newline stripped from edits, secrets at the project root not denied,
  `terminal_last_output` returning the slowest command, a split `</think>` cutting off Ask-tab replies.

### Update: Llamatik library 2.4.0, and why `:backends` is plain JDK code

`LlamatikBackendService` is now a thin wrapper around `LocalLlamaBackend` (one local engine for plugin, CLI and ACP).

The server client is **not** the library's `com.llamatik:network`, although 2.4.0 publishes it. It was tried and
failed inside the IDE: IntelliJ 2026.1 ships coroutines 1.10.2 and ktor 3.3.2, the library is built against
coroutines 1.11 and ktor 3.6.1, and the IDE's classloader prefers its own copies. Result: `NoSuchMethodError` on
`BuildersKt.runBlockingK` (coroutines 1.11 renamed the JVM method) and on ktor's `TypeInfo` constructor. Bundling
newer copies does not help. So `:backends` keeps a JDK `HttpClient` implementation with the same resume protocol
(stream id + `Last-Event-ID`, see `remote/LlamatikServerClient`), tested against an in-process server.
`RemoteBackendOnPlatformTest` runs it on the plugin's real classpath and fails if the network library or Gradle's
coroutines ever reach it. Revisit when the platform's ktor/coroutines catch up with the library.

Discovery also avoids the library's `NodeDiscovery`: `JmDNS.create()` binds to whatever interface it picks first
(a VPN tunnel on many Macs), so `LanMdns` binds to each real LAN interface instead.
