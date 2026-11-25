package com.llamatik.cli

import com.llamatik.agent.backends.InferenceParams
import com.llamatik.agent.core.agent.CodingLimits
import java.nio.file.Path

/** Everything the command line can ask for. */
data class CliOptions(
    val model: String? = null,
    val server: String? = null,
    /** Look for Llamatik servers on the local network and pick one (instead of `--server`). */
    val discover: Boolean = false,
    /** One-shot prompt (`-p`); null starts the interactive session. */
    val prompt: String? = null,
    val workDir: Path = Path.of("").toAbsolutePath(),
    /** Approve every edit and command without asking. */
    val yes: Boolean = false,
    val aggressive: Boolean = false,
    /** Print every model reply as it arrives (for diagnosing odd behaviour). */
    val debug: Boolean = false,
    /** Keep the native library's own log output (hidden by the launcher unless this is set). */
    val nativeLogs: Boolean = false,
    val params: InferenceParams = InferenceParams(),
    /** Inference values given as flags, so they win over the settings files. */
    val flagOverrides: InferenceOverrides = InferenceOverrides(),
    /** Agent-loop values given as flags. */
    val agentFlags: AgentOverrides = AgentOverrides(),
    /** How the agent loop behaves (steps, budgets, ...); set from the settings files and flags. */
    val limits: CodingLimits = CodingLimits(),
    /** An extra settings file (`--config`) read after the standard ones. */
    val configFile: Path? = null,
    /** Settings files that were read, and warnings about them, for `/config`. */
    val configFiles: List<String> = emptyList(),
    val configWarnings: List<String> = emptyList(),
    val help: Boolean = false,
    val version: Boolean = false,
)

class UsageException(message: String) : Exception(message)

/** The options with the settings files folded in: defaults < files < flags. */
fun CliOptions.withSettings(loaded: LoadedSettings): CliOptions {
    val s = loaded.settings
    // A model or server given as a flag replaces the one in the files (they cannot be combined).
    val flagPicksBackend = model != null || server != null || discover
    val model = if (flagPicksBackend) model else s.model
    val server = if (flagPicksBackend) server else s.server
    if (model != null && server != null) throw SettingsException("Settings name both a model and a server; keep one.")
    return copy(
        model = model, server = server,
        yes = yes || s.autoApprove == true,
        params = s.inference.over(flagOverrides).applyTo(InferenceParams()),
        limits = s.agent.over(agentFlags).applyTo(CodingLimits()),
        configFiles = loaded.files, configWarnings = loaded.warnings,
    )
}

object CliArgs {
    fun parse(args: List<String>): CliOptions {
        var o = CliOptions()
        var p = o.params
        var f = o.flagOverrides
        var af = o.agentFlags
        var i = 0
        fun value(flag: String): String {
            if (i + 1 >= args.size) throw UsageException("Missing value for $flag")
            return args[++i]
        }
        fun int(flag: String): Int = value(flag).toIntOrNull() ?: throw UsageException("$flag needs a whole number")
        while (i < args.size) {
            val a = args[i]
            // --flag=value form
            val (flag, inline) = if (a.startsWith("--") && '=' in a) a.substringBefore('=') to a.substringAfter('=') else a to null
            fun v(): String = inline ?: value(flag)
            fun n(): Int = inline?.toIntOrNull() ?: if (inline != null) throw UsageException("$flag needs a whole number") else int(flag)
            when (flag) {
                "-h", "--help" -> o = o.copy(help = true)
                "--version" -> o = o.copy(version = true)
                "-m", "--model" -> o = o.copy(model = v())
                "-s", "--server" -> o = o.copy(server = v())
                "--discover" -> o = o.copy(discover = true)
                "-p", "--prompt" -> o = o.copy(prompt = v())
                "-C", "--cwd" -> o = o.copy(workDir = Path.of(v()).toAbsolutePath().normalize())
                "-y", "--yes" -> o = o.copy(yes = true)
                "--aggressive" -> o = o.copy(aggressive = true)
                "--debug" -> o = o.copy(debug = true)
                "--native-logs" -> o = o.copy(nativeLogs = true)
                "--config" -> o = o.copy(configFile = Path.of(v()).toAbsolutePath().normalize())
                "--temperature" -> { val x = v().toDoubleOrNull() ?: throw UsageException("--temperature needs a number"); p = p.copy(temperature = x); f = f.copy(temperature = x) }
                "--max-tokens" -> { val x = n(); p = p.copy(maxTokens = x); f = f.copy(maxTokens = x) }
                "--context" -> { val x = n(); p = p.copy(contextSize = x); f = f.copy(contextSize = x) }
                "--threads" -> { val x = n(); p = p.copy(threads = x); f = f.copy(threads = x) }
                "--gpu-layers" -> { val x = n(); p = p.copy(gpuLayers = x); f = f.copy(gpuLayers = x) }
                "--batch-size" -> { val x = n(); p = p.copy(batchSize = x); f = f.copy(batchSize = x) }
                "--top-p" -> { val x = v().toDoubleOrNull() ?: throw UsageException("--top-p needs a number"); p = p.copy(topP = x); f = f.copy(topP = x) }
                "--top-k" -> { val x = n(); p = p.copy(topK = x); f = f.copy(topK = x) }
                "--repeat-penalty" -> { val x = v().toDoubleOrNull() ?: throw UsageException("--repeat-penalty needs a number"); p = p.copy(repeatPenalty = x); f = f.copy(repeatPenalty = x) }
                "--flash-attention" -> { p = p.copy(flashAttention = true); f = f.copy(flashAttention = true) }
                "--no-mmap" -> { p = p.copy(useMmap = false); f = f.copy(useMmap = false) }
                "--max-steps" -> { val x = n(); af = af.copy(maxSteps = x) }
                "--think" -> af = af.copy(thinking = true)
                else -> throw UsageException(
                    if (a.startsWith("-")) "Unknown option: $a"
                    else "Unexpected argument: $a (put the prompt after -p)",
                )
            }
            i++
        }
        if (o.discover && (o.server != null || o.model != null)) {
            throw UsageException("--discover looks for a server by itself; do not combine it with --server or --model")
        }
        return o.copy(params = p, flagOverrides = f, agentFlags = af)
    }

    val USAGE = """
        |Usage: llamatik [options]
        |
        |Runs the Llamatik coding agent in your terminal, on a local model or a Llamatik server.
        |
        |Model (one of):
        |  -m, --model <file.gguf>     run a local GGUF model in-process
        |  -s, --server <host[:port]>  use a Llamatik server (e.g. the Llamatik app with "Share this device")
        |      --discover              scan the local network for Llamatik servers and choose one
        |
        |Session:
        |  -p, --prompt <text>         run one task and exit (otherwise an interactive session starts)
        |  -C, --cwd <dir>             project directory (default: current directory)
        |  -y, --yes                   apply edits and run commands without asking
        |      --aggressive            use the aggressive execution mode
        |      --debug                 print each raw model reply
        |      --native-logs           show llama.cpp's own log output (hidden by default)
        |
        |Inference (the same options as the Llamatik app):
        |      --temperature <n>       sampling temperature (default 0.7)
        |      --max-tokens <n>        tokens per reply (default 8192)
        |      --context <n>           context size in tokens (default 16384)
        |      --batch-size <n>        prompt batch size (default 2048)
        |      --top-p <n>             nucleus sampling (default 0.95)
        |      --top-k <n>             top-k sampling (default 40)
        |      --repeat-penalty <n>    repetition penalty (default 1.1)
        |      --threads <n>           CPU threads
        |      --gpu-layers <n>        layers offloaded to the GPU (0 = CPU only, -1 = all)
        |      --flash-attention       enable flash attention
        |      --no-mmap               do not memory-map the model file
        |
        |Agent:
        |      --max-steps <n>         model calls allowed per task (default 30)
        |      --think                 let reasoning models think before each reply (slower)
        |
        |Settings files (JSON, flags win over them; see /config in a session):
        |  ~/.llamatik/settings.json                 your defaults for every project
        |  <project>/.llamatik/settings.json         shared with the project
        |  <project>/.llamatik/settings.local.json   yours, for this project only
        |      --config <file>         read one more settings file on top of those
        |
        |  -h, --help                  show this help
        |      --version               show the version
        |
        |In a session: /help, /config (show settings), /clear (forget the conversation), /yes (toggle auto-approve), /exit
    """.trimMargin()
}
