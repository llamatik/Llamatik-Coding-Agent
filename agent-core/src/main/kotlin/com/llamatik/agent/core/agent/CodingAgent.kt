package com.llamatik.agent.core.agent

import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.backend.SessionTag
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolCallParser
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** A tool the model is told about: [usage] lists its arguments. */
data class ToolSpec(val name: String, val description: String, val usage: String = "")

/** What a [CodingAgent] reports while it works; a front-end draws it. All methods are called on the agent's thread. */
interface CodingListener {
    /** The next request carries about [estimatedTokens] tokens of prompt (the model reads them before it writes). */
    fun onPrompt(estimatedTokens: Int) {}
    /** A request to the model starts (a spinner can run until [onText] or [onGenerated]). */
    fun onGenerating(step: Int) {}
    /** Number of tokens received so far in the current request. */
    fun onTokens(count: Int) {}
    /** Text meant for the user, as it is written. */
    fun onText(text: String) {}
    /** The request is over; [onToolCall] / the next [onGenerating] follow, or the run ends. */
    fun onGenerated(step: Int) {}
    fun onToolCall(call: ToolCall) {}
    fun onToolResult(call: ToolCall, result: ToolResult) {}
    /** Something the user should know that is not model text (a retry, a limit). */
    fun onNotice(message: String) {}
}

/** Lets another thread stop a running [CodingAgent]. */
class RunControl {
    @Volatile var cancelled = false
        private set
    @Volatile private var active: GenerationHandle? = null
    @Volatile private var waiting: CompletableFuture<*>? = null

    fun cancel() {
        cancelled = true
        active?.cancel()
        waiting?.cancel(true)
    }

    internal fun attach(handle: GenerationHandle, future: CompletableFuture<*>) {
        active = handle
        waiting = future
        if (cancelled) cancel()
    }

    internal fun detach() { active = null; waiting = null }
}

sealed interface CodingOutcome {
    /** The model finished: its last reply is the answer. */
    data object Done : CodingOutcome
    data object Cancelled : CodingOutcome
    data class Failed(val message: String) : CodingOutcome
    /** Stopped after [steps] model calls without an answer. */
    data class StepLimit(val steps: Int) : CodingOutcome
}

data class CodingLimits(
    val maxSteps: Int = 30,
    val maxToolCallsPerStep: Int = 6,
    /** Blank replies tolerated in a row before giving up. */
    val maxEmptyReplies: Int = 2,
    /** The same call made again within this many steps is answered with a nudge instead of being run again. */
    val repeatWindow: Int = 2,
)

/**
 * An interactive coding agent in the style of Claude Code or OpenCode: the model looks at the project with
 * tools, reads what the tools return, edits, runs checks, and only stops when it has an answer. There is no
 * fixed number of rounds and no intent guessing; the conversation (including every tool result) is the
 * state, and the model decides what to do next.
 *
 * Tool calls use `<tool_call name="x"><arg name="k">v</arg></tool_call>`; results come back to the model as a
 * `<tool_result>` message. Edits happen inside [tools] (see [EditApplyingToolExecutor]).
 */
class CodingAgent(
    private val backend: AssistantBackend,
    private val env: AgentEnvironment,
    private val tools: ToolExecutor,
    private val toolSpecs: List<ToolSpec>,
    private val sources: PromptSources,
    private val limits: CodingLimits = CodingLimits(),
) {
    private val toolNames = toolSpecs.map { it.name }.toSet()

    fun run(
        session: ChatSession,
        userPrompt: String,
        config: BackendConfig,
        listener: CodingListener,
        control: RunControl = RunControl(),
    ): CodingOutcome {
        session.messages += ChatMessage(role = ChatRole.USER, content = withAttachments(userPrompt, config))
        val system = buildSystemPrompt()
        // Tool calling is more reliable when sampling is not too loose.
        val genConfig = config.copy(temperature = minOf(config.temperature, MAX_AGENT_TEMPERATURE))
        // Step at which each call was last made: a repeat is only pointless while its result is still in view.
        val lastSeen = HashMap<String, Int>()
        var emptyReplies = 0
        var malformedReplies = 0
        var nudge: ChatMessage? = null
        var step = 0

        while (step < limits.maxSteps) {
            if (control.cancelled) return CodingOutcome.Cancelled
            step++
            val messages = fitHistory(session.messages, system, config) + listOfNotNull(nudge)
            nudge = null
            val stepConfig = genConfig.copy(maxTokens = outputTokens(genConfig, system.length + messages.sumOf { it.content.length }))
            listener.onPrompt(((system.length + messages.sumOf { it.content.length }) / CHARS_PER_TOKEN).toInt())
            listener.onGenerating(step)
            val reply = try {
                generate(system, messages, stepConfig, listener, control)
            } catch (_: CancellationException) {
                return CodingOutcome.Cancelled
            } catch (e: GenerationFailed) {
                return CodingOutcome.Failed(e.message ?: "Generation failed")
            } finally {
                control.detach()
                listener.onGenerated(step)
            }
            if (control.cancelled) return CodingOutcome.Cancelled

            val visible = ToolCallParser.stripThinking(reply).trim()
            val calls = parseCalls(visible)
            if (calls.isEmpty() && LOST_OPENING.containsMatchIn(visible) && malformedReplies < MAX_MALFORMED) {
                // The reply began with a tool call whose opening never arrived (see AgentStreamFilter): ask again.
                malformedReplies++
                listener.onNotice("The model's tool call was cut off; asking again…")
                nudge = ChatMessage(role = ChatRole.USER, content = RETRY_NUDGE)
                step--
                continue
            }
            if (calls.isEmpty()) {
                if (visible.isBlank()) {
                    if (++emptyReplies > limits.maxEmptyReplies) return CodingOutcome.Failed("The model produced no output.")
                    listener.onNotice("The model returned nothing; retrying…")
                    step-- // a blank reply is not a step of work
                    continue
                }
                session.messages += ChatMessage(role = ChatRole.ASSISTANT, content = visible)
                return CodingOutcome.Done
            }
            emptyReplies = 0
            malformedReplies = 0
            session.messages += ChatMessage(role = ChatRole.ASSISTANT, content = visible.substringUpToLastCall())

            val results = mutableListOf<Pair<ToolCall, ToolResult>>()
            for (call in calls.take(limits.maxToolCallsPerStep)) {
                if (control.cancelled) return CodingOutcome.Cancelled
                listener.onToolCall(call)
                val key = call.name + call.arguments.toSortedMap().toString()
                val repeated = lastSeen.put(key, step)?.let { step - it <= limits.repeatWindow } == true
                val result = when {
                    call.name !in toolNames -> ToolResult(call.name, false,
                        "Error: unknown tool '${call.name}'. Available tools: ${toolSpecs.joinToString(", ") { it.name }}.")
                    repeated && call.name != "terminal_run" -> ToolResult(call.name, false,
                        "Error: you already made this exact call and have its result above. Use that result, try something different, or answer now.")
                    else -> tools.execute(call, ToolExecutionContext(env))
                }
                listener.onToolResult(call, result)
                results += call to result
            }
            session.messages += ChatMessage(role = ChatRole.TOOL, content = formatResults(results, config))
        }
        return CodingOutcome.StepLimit(limits.maxSteps)
    }

    // ── Model call ───────────────────────────────────────────────────────────

    private class GenerationFailed(message: String) : Exception(message)

    /** One blocking request; streams the visible text to [listener] and returns the raw reply. */
    private fun generate(
        system: String,
        messages: List<ChatMessage>,
        config: BackendConfig,
        listener: CodingListener,
        control: RunControl,
    ): String {
        val future = CompletableFuture<String>()
        val filter = AgentStreamFilter { if (!control.cancelled) listener.onText(it) }
        var tokens = 0
        // A backend may deliver the reply in one piece (no token stream): show it from the final text then.
        var streamed = false
        val handle = backend.generate(
            GenerationRequest(system, messages, config, noThink = true, sessionTag = SessionTag.AGENT),
            object : GenerationCallbacks {
                override fun onToken(token: String) {
                    if (control.cancelled) return
                    streamed = true
                    listener.onTokens(++tokens)
                    filter.accept(token)
                }

                override fun onCompleted(fullText: String) {
                    if (!streamed) filter.accept(fullText)
                    filter.finish()
                    future.complete(fullText)
                }

                override fun onError(message: String, throwable: Throwable?) {
                    future.completeExceptionally(GenerationFailed(message))
                }
            },
        )
        control.attach(handle, future)
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ── Tool calls ───────────────────────────────────────────────────────────

    private fun parseCalls(text: String): List<ToolCall> {
        if (!text.contains("<tool_call", ignoreCase = true)) return emptyList()
        val json = ToolCallParser.parseJsonForm(text)
        if (json.isNotEmpty()) return json
        return ToolCallParser.parseIncludingIncomplete(text)
    }

    /** Anything the model wrote after its last tool call is invented (it cannot know the results yet). */
    private fun String.substringUpToLastCall(): String {
        val close = lastIndexOf("</tool_call>", ignoreCase = true)
        return if (close >= 0) substring(0, close + "</tool_call>".length) else this
    }

    private fun formatResults(results: List<Pair<ToolCall, ToolResult>>, config: BackendConfig): String {
        val cap = resultCap(config)
        return results.joinToString("\n\n") { (call, result) ->
            val body = result.content.ifBlank { "(no output)" }
            val shown = if (body.length > cap) body.take(cap) + "\n[… ${body.length - cap} more characters not shown]" else body
            val text = if (!result.success && !shown.startsWith("Error")) "Error: $shown" else shown
            "<tool_result name=\"${call.name}\">\n$text\n</tool_result>"
        }
    }

    // ── Context ──────────────────────────────────────────────────────────────

    private fun outputReserve(config: BackendConfig) = minOf(config.maxTokens, config.contextLength / 4)

    /** Output allowance for one request: what the context still has room for after the prompt. */
    private fun outputTokens(config: BackendConfig, promptChars: Int): Int {
        val room = config.contextLength - (promptChars / CHARS_PER_TOKEN).toInt() - OUTPUT_MARGIN_TOKENS
        return minOf(config.maxTokens, room).coerceAtLeast(MIN_OUTPUT_TOKENS)
    }

    /** Room for one tool result: a fifth of the context window, at most 16K characters. */
    private fun resultCap(config: BackendConfig) = (config.contextLength * CHARS_PER_TOKEN / 5).toInt().coerceIn(3_000, 16_000)

    /**
     * The conversation as it goes to the model: when it would not fit, old tool results shrink to a stub
     * first, then the oldest turns are dropped. The session itself is left whole.
     */
    internal fun fitHistory(all: List<ChatMessage>, system: String, config: BackendConfig): List<ChatMessage> {
        // The runtime counts the whole output allowance against the context, so it has to stay free.
        val reserved = outputReserve(config)
        val budget = ((config.contextLength - reserved) * CHARS_PER_TOKEN * 0.8).toInt() - system.length
        val messages = all.toMutableList()
        fun size() = messages.sumOf { it.content.length }

        // 1. Shrink tool output, oldest first, sparing the newest results as long as possible.
        for (keep in intArrayOf(KEEP_RECENT_RESULTS, 1, 0)) {
            if (size() <= budget) break
            val toolIdx = messages.indices.filter { messages[it].role == ChatRole.TOOL }
            for (i in toolIdx.dropLast(keep)) {
                if (size() <= budget) break
                if (messages[i].content.length > STUB.length) messages[i] = messages[i].copy(content = STUB)
            }
        }
        // 2. Drop earlier turns, but never the task being worked on (the latest user message).
        while (size() > budget) {
            val task = messages.indexOfLast { it.role == ChatRole.USER }
            if (task <= 0) break
            messages.removeAt(0)
            while (messages.size > 1 && messages.first().role != ChatRole.USER) messages.removeAt(0)
        }
        // 3. Still too big: cut the largest message down.
        if (size() > budget) {
            val i = messages.indices.maxByOrNull { messages[it].content.length }!!
            val keep = (messages[i].content.length - (size() - budget)).coerceAtLeast(MIN_KEPT_CHARS)
            messages[i] = messages[i].copy(content = messages[i].content.take(keep) + "\n[… cut to fit the context]")
        }
        return messages
    }

    /** Expands `@path` / `@{path}` mentions of project files into the message, as the user attached them. */
    private fun withAttachments(prompt: String, config: BackendConfig): String {
        val mentions = MENTION.findAll(prompt).map { (it.groups[1] ?: it.groups[2])!!.value }.distinct().toList()
        val cap = resultCap(config)
        val attached = mentions.mapNotNull { path ->
            val text = runCatching { env.files.read(path) }.getOrNull() ?: return@mapNotNull null
            "<file path=\"$path\">\n${text.take(cap)}${if (text.length > cap) "\n[… truncated]" else ""}\n</file>"
        }
        if (attached.isEmpty()) return prompt
        return prompt + "\n\n" + attached.joinToString("\n\n")
    }

    // ── Prompt ───────────────────────────────────────────────────────────────

    internal fun buildSystemPrompt(): String = buildString {
        appendLine("You are Llamatik Code, a coding agent running in the user's terminal on a local model. You help with software engineering tasks in their project by exploring it, reading and changing code, and running checks, using the tools below.")
        appendLine()
        appendLine("# How to work")
        appendLine("- Look before you answer or edit. Use the tools to find and read the real code; never guess file names, paths or contents.")
        appendLine("- Start from what is relevant: list_files for the layout, search_code / search_filenames to locate things, read_file to read them. Read a file before you change it.")
        appendLine("- Change code with replace_in_file: oldText must be copied EXACTLY from the file (read it first) and be small but unique. Use write_file only to rewrite a whole file and create_file for a new one. The user approves every edit, and you are told the outcome.")
        appendLine("- Never conclude something is missing after one failed search: try other terms, or read the file that most likely contains it, before you answer.")
        appendLine("- After a change, check it when you can (terminal_run with the project's build or test command) and fix what breaks.")
        appendLine("- Keep working with tools until the task is done. Do not ask permission to explore, and do not stop to describe a plan you could simply carry out.")
        appendLine("- If the user asks a question, answer it from the code you have read. If it is a general question or chat, answer directly without tools.")
        appendLine("- Your final reply is a short, plain summary of what you found or changed (mention file paths). No filler, no repeating the code you wrote.")
        appendLine()
        appendLine("# Using tools")
        appendLine("To use a tool, write a tool call. ALWAYS begin your reply with one short sentence saying what you are about to do (for example \"Let me search for it.\"), then the tool call(s). Never start a reply directly with a tool call.")
        appendLine("<tool_call name=\"TOOL\"><arg name=\"ARG\">VALUE</arg></tool_call>")
        appendLine("Argument values are raw text: no quotes or escaping, multi-line values are fine. You may write several tool calls in one reply. After the tool calls STOP: the results arrive in the next message as <tool_result>. Never write <tool_result> yourself and never guess what a tool will return.")
        appendLine("Paths are relative to the project root.")
        appendLine()
        appendLine("Tools:")
        toolSpecs.forEach { spec ->
            append("- ${spec.name}: ${spec.description}")
            if (spec.usage.isNotBlank()) append(" Arguments: ${spec.usage}.")
            appendLine()
        }
        appendLine()
        appendLine("Example:")
        appendLine("Let me find where this is defined.")
        appendLine("<tool_call name=\"search_code\"><arg name=\"query\">class UserRepository</arg></tool_call>")
        appendLine()
        appendLine("# Environment")
        appendLine("Project: ${sources.projectName().ifBlank { env.files.rootPath }} (${env.files.rootPath})")
        val overview = runCatching { env.files.list(null) }.getOrDefault(emptyList()).take(OVERVIEW_MAX_LINES)
        if (overview.isNotEmpty()) {
            appendLine("Top level:")
            overview.forEach { appendLine("  $it") }
        }
        val rules = sources.rulesPromptBlock()
        if (rules.isNotBlank()) { appendLine(); append(rules) }
        val memory = sources.memoryPromptBlock()
        if (memory.isNotBlank()) { appendLine(); append(memory) }
    }

    private companion object {
        /** Source code tokenizes at roughly 2.5-3 characters per token; assume the denser one. */
        const val CHARS_PER_TOKEN = 2.5
        const val MAX_MALFORMED = 2
        const val OUTPUT_MARGIN_TOKENS = 256
        const val MIN_OUTPUT_TOKENS = 512
        const val RETRY_NUDGE = "Your last reply was cut off. Reply again: first one short sentence about what you are doing, then the tool call."
        /** The tail of a tool call whose opening was lost: a closing tag with nothing opened before it. */
        val LOST_OPENING = Regex("""^(?![\s\S]*<tool_call)[\s\S]*</(arg|tool_call)>""", RegexOption.IGNORE_CASE)
        const val MAX_AGENT_TEMPERATURE = 0.3
        const val KEEP_RECENT_RESULTS = 3
        const val MIN_KEPT_CHARS = 1_000
        const val OVERVIEW_MAX_LINES = 40
        const val STUB = "[earlier tool output removed to save context; call the tool again if you need it]"
        val MENTION = Regex("""@\{([^}\s]+)}|(?<![\w@])@([\w./-]*[\w-]\.\w+)""")
    }
}
