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
    /** More calls than this in one reply are not run (their results would not fit the context together). */
    val maxToolCallsPerStep: Int = 3,
    /** Blank replies tolerated in a row before giving up. */
    val maxEmptyReplies: Int = 2,
    /** Steps spent only reading and searching, without an edit or a command, before the agent is told to stop exploring. */
    val maxExploreSteps: Int = 8,
    /** Calls repeated from earlier in the run (their output was shortened away) before the agent is told to stop exploring. */
    val maxReReads: Int = 3,
    /** The same call made again within this many steps is answered with a nudge instead of being run again. */
    val repeatWindow: Int = 2,
    /** Sampling temperature is capped to this while the agent works: tool calls are more reliable when it is low. */
    val temperatureCap: Double = 0.3,
    /** Source code tokenizes at roughly 2.5-3 characters per token; the lower value is the safe one. Sizes the budgets. */
    val charsPerToken: Double = 2.5,
    /** Let reasoning models think before each reply (slower, usually better plans). */
    val thinking: Boolean = false,
    /** Largest tool result sent to the model, in characters (also limited to a fifth of the context window). */
    val maxResultChars: Int = 16_000,
    /** Share of the context window kept free for the reply, at most (the reply is also capped to what fits). */
    val outputReserveShare: Double = 0.25,
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
        val genConfig = config.copy(temperature = minOf(config.temperature, limits.temperatureCap))
        // Step at which each call was last made: a repeat is only pointless while its result is still in view.
        val lastSeen = HashMap<String, Int>()
        var emptyReplies = 0
        var malformedReplies = 0
        var intentNudges = 0
        var nudge: ChatMessage? = null
        var step = 0
        // Run-level brakes against endless exploring (see EXPLORE_BRAKE).
        var reReads = 0
        var exploring = 0
        var braked = false
        var stepsAfterBrake = 0
        var forceAnswer = false
        // A request to change code that ends in a description of the change has not been carried out.
        var everActed = false
        var actionNudges = 0
        val wantsChange = WANTS_CHANGE.containsMatchIn(userPrompt) && !QUESTION.containsMatchIn(userPrompt.trim())

        while (step < limits.maxSteps) {
            if (control.cancelled) return CodingOutcome.Cancelled
            step++
            if (forceAnswer) nudge = ChatMessage(role = ChatRole.USER, content = FINAL_NUDGE)
            val messages = fitHistory(session.messages, system, config) + listOfNotNull(nudge)
            nudge = null
            val stepConfig = genConfig.copy(maxTokens = outputTokens(genConfig, system.length + messages.sumOf { it.content.length }))
            listener.onPrompt(((system.length + messages.sumOf { it.content.length }) / limits.charsPerToken).toInt())
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
            if (forceAnswer) {
                // Tool calls are no longer wanted: whatever the model said before them is its answer.
                val answer = visible.substringBefore("<tool_call").trim().ifBlank { GAVE_UP_ANSWER }
                session.messages += ChatMessage(role = ChatRole.ASSISTANT, content = answer)
                if (answer == GAVE_UP_ANSWER) listener.onText(answer)
                return CodingOutcome.Done
            }
            val calls = parseCalls(visible)
            if (calls.isEmpty() && LOST_OPENING.containsMatchIn(visible) && malformedReplies < MAX_MALFORMED) {
                // The reply began with a tool call whose opening never arrived (see AgentStreamFilter): ask again.
                malformedReplies++
                listener.onNotice("The model's tool call was cut off; asking again…")
                nudge = ChatMessage(role = ChatRole.USER, content = RETRY_NUDGE)
                step--
                continue
            }
            if (calls.isEmpty() && visible.length < INTENT_MAX_CHARS && INTENT.containsMatchIn(visible) && intentNudges < MAX_INTENT_NUDGES) {
                // "Let me analyze…" and then nothing: the model announced an action instead of taking it.
                intentNudges++
                session.messages += ChatMessage(role = ChatRole.ASSISTANT, content = visible)
                session.messages += ChatMessage(role = ChatRole.USER, content = INTENT_NUDGE)
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
                if (wantsChange && !everActed && actionNudges < MAX_ACTION_NUDGES && step < limits.maxSteps) {
                    actionNudges++
                    session.messages += ChatMessage(role = ChatRole.USER, content = ACTION_NUDGE)
                    exploring = 0; reReads = 0; braked = false; stepsAfterBrake = 0
                    continue
                }
                return CodingOutcome.Done
            }
            emptyReplies = 0
            malformedReplies = 0
            session.messages += ChatMessage(role = ChatRole.ASSISTANT, content = visible.substringUpToLastCall())

            val results = mutableListOf<Pair<ToolCall, ToolResult>>()
            for ((index, call) in calls.withIndex()) {
                if (control.cancelled) return CodingOutcome.Cancelled
                if (index >= limits.maxToolCallsPerStep) {
                    results += call to ToolResult(call.name, false,
                        "Error: not run. At most ${limits.maxToolCallsPerStep} tool calls per reply; make this call in your next reply.")
                    continue
                }
                listener.onToolCall(call)
                val key = call.name + call.arguments.toSortedMap().toString()
                val previous = lastSeen.put(key, step)
                val repeated = previous != null && step - previous <= limits.repeatWindow
                if (previous != null && call.name !in ACTION_TOOLS) reReads++
                val result = when {
                    call.name !in toolNames -> ToolResult(call.name, false,
                        "Error: unknown tool '${call.name}'. Available tools: ${toolSpecs.joinToString(", ") { it.name }}.")
                    repeated && call.name != "terminal_run" -> ToolResult(call.name, false,
                        "Error: you already made this exact call and have its result above. Use that result, try something different, or answer now.")
                    else -> try {
                        tools.execute(call, ToolExecutionContext(env))
                    } catch (e: Exception) {
                        ToolResult(call.name, false, "Error: ${call.name} failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                listener.onToolResult(call, result)
                results += call to result
            }
            // Progress is a change or a command; anything else is exploration.
            val acted = results.any { (call, r) -> r.success && call.name in ACTION_TOOLS && !r.content.startsWith("No change") }
            if (acted) everActed = true
            if (acted) { exploring = 0; reReads = 0; braked = false; stepsAfterBrake = 0 } else exploring++
            var text = formatResults(results, system, config)
            if (!braked && !acted && (exploring >= limits.maxExploreSteps || reReads >= limits.maxReReads)) {
                braked = true
                stepsAfterBrake = 0
                text += "\n\n" + EXPLORE_BRAKE
            } else if (braked && !acted && ++stepsAfterBrake >= BRAKE_PATIENCE) {
                forceAnswer = true
            }
            session.messages += ChatMessage(role = ChatRole.TOOL, content = text)
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
            GenerationRequest(system, messages, config, noThink = !limits.thinking, sessionTag = SessionTag.AGENT),
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

    private fun formatResults(results: List<Pair<ToolCall, ToolResult>>, system: String, config: BackendConfig): String {
        // The results of one reply share half of what the history may hold, so none of them crowds out the rest.
        val cap = minOf(resultCap(config), (historyBudget(system, config) / 2 / results.size.coerceAtLeast(1))).coerceAtLeast(MIN_RESULT_CHARS)
        return results.joinToString("\n\n") { (call, result) ->
            val shown = clip(call, result.content.ifBlank { "(no output)" }, cap)
            val text = if (!result.success && !shown.startsWith("Error")) "Error: $shown" else shown
            "<tool_result name=\"${call.name}\"${argAttribute(call)}>\n$text\n</tool_result>"
        }
    }

    /** The main argument of a call (path, query, command), recorded in its result so a shortened result still says what it was. */
    private fun argAttribute(call: ToolCall): String {
        val main = call.arguments["path"] ?: call.arguments["query"] ?: call.arguments["command"] ?: return ""
        return " arg=\"${main.lineSequence().first().take(120).replace("\"", "'")}\""
    }

    /** Cuts [body] to [cap] characters and says how to get to the rest. */
    private fun clip(call: ToolCall, body: String, cap: Int): String {
        if (body.length <= cap) return body
        val wholeFile = call.name == "read_file" && call.arguments["offset"] == null && call.arguments["limit"] == null
        if (wholeFile) return outline(body, cap)
        val cut = body.lastIndexOf('\n', cap).takeIf { it > cap / 2 } ?: cap
        val shown = body.substring(0, cut)
        if (call.name == "read_file") {
            val first = call.arguments["offset"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val last = first + shown.count { it == '\n' }
            return "$shown\n[… cut to fit: showing lines $first-$last. Call read_file with offset=${last + 1} to continue.]"
        }
        return "$shown\n[… ${body.length - cut} more characters not shown]"
    }

    /**
     * A file too big to send whole: its first lines and a numbered map of its declarations, which is what a
     * person would look at first. Paging through thousands of lines costs a step per screen and finds nothing.
     */
    private fun outline(body: String, cap: Int): String {
        val lines = body.lines()
        val head = lines.take(OUTLINE_HEAD_LINES).joinToString("\n").take(cap / 4)
        val header = "[The file has ${lines.size} lines, too many to show at once. First lines, then a map of its declarations as L<line>. " +
            "To see a part, call read_file with offset=<line> and limit=<lines>; to find something by name, use search_code.]\n"
        var room = cap - header.length - head.length - 32
        val map = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (!DECLARATION.containsMatchIn(line)) continue
            val entry = "L${i + 1} ${line.trim().take(OUTLINE_ENTRY_CHARS)}\n"
            if (entry.length > room) { map.append("… (map cut; search_code finds the rest)"); break }
            map.append(entry); room -= entry.length
        }
        return header + head + "\n…\n" + map.toString().trimEnd()
    }

    // ── Context ──────────────────────────────────────────────────────────────

    private fun outputReserve(config: BackendConfig) = minOf(config.maxTokens, (config.contextLength * limits.outputReserveShare).toInt())

    /** Characters the conversation may take next to [system], leaving the output allowance free. */
    private fun historyBudget(system: String, config: BackendConfig): Int =
        ((config.contextLength - outputReserve(config)) * limits.charsPerToken * 0.8).toInt() - system.length

    /** Output allowance for one request: what the context still has room for after the prompt. */
    private fun outputTokens(config: BackendConfig, promptChars: Int): Int {
        val room = config.contextLength - (promptChars / limits.charsPerToken).toInt() - OUTPUT_MARGIN_TOKENS
        return minOf(config.maxTokens, room).coerceAtLeast(MIN_OUTPUT_TOKENS)
    }

    /** Room for one tool result: a fifth of the context window, at most 16K characters. */
    private fun resultCap(config: BackendConfig) = (config.contextLength * limits.charsPerToken / 5).toInt().coerceIn(MIN_RESULT_CHARS, limits.maxResultChars.coerceAtLeast(MIN_RESULT_CHARS))

    /**
     * The conversation as it goes to the model: when it would not fit, old tool results shrink to a stub
     * first, then the oldest turns are dropped. The session itself is left whole.
     */
    internal fun fitHistory(all: List<ChatMessage>, system: String, config: BackendConfig): List<ChatMessage> {
        // The runtime counts the whole output allowance against the context, so it has to stay free.
        val budget = historyBudget(system, config)
        val messages = all.toMutableList()
        fun size() = messages.sumOf { it.content.length }

        // 1. Shrink tool output, oldest first, sparing the newest results as long as possible.
        // The newest results are never stubbed: the model has to see what it just asked for.
        for (keep in intArrayOf(KEEP_RECENT_RESULTS, 1)) {
            if (size() <= budget) break
            val toolIdx = messages.indices.filter { messages[it].role == ChatRole.TOOL }
            for (i in toolIdx.dropLast(keep)) {
                if (size() <= budget) break
                messages[i] = messages[i].copy(content = shorten(messages[i].content))
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

    /**
     * What stays of an old tool result: not the content, but enough to know it was seen and where things are
     * (a file's declarations, the first search hits). Without that the model forgets and reads everything again.
     */
    internal fun shorten(content: String): String {
        if (SHORTENED in content) return content
        val results = RESULT.findAll(content).toList()
        if (results.isEmpty()) return if (content.length > STUB.length) STUB else content
        val digest = results.joinToString("\n\n") { m ->
            val name = m.groupValues[1]
            val arg = m.groupValues[2]
            val lines = m.groupValues[3].lines()
            val body = when (name) {
                "read_file" -> {
                    val outline = lines.filter { DECLARATION.containsMatchIn(it) }.map { it.trim().take(100) }.distinct().take(OUTLINE_LINES)
                    "${lines.size} lines." + if (outline.isEmpty()) "" else " Declarations:\n" + outline.joinToString("\n") { "  $it" }
                }
                "search_code", "search_filenames", "list_files" ->
                    lines.take(DIGEST_LINES).joinToString("\n") + if (lines.size > DIGEST_LINES) "\n… (${lines.size} lines in all)" else ""
                else -> m.groupValues[3].take(200)
            }
            "<tool_result name=\"$name\"${if (arg.isNotEmpty()) " arg=\"$arg\"" else ""}>\n$SHORTENED\n$body\n</tool_result>"
        }
        return if (digest.length < content.length) digest else content
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
        appendLine("- Start from what is relevant: list_files for the layout, search_code / search_filenames to locate things, read_file to read them. Read a file before you change it. Do not read a file twice: older tool output is shortened to a summary, which is enough to remember what is where. As soon as you know where to change something, make the change. Make at most 3 tool calls per reply; a long file comes back as a map of its declarations with line numbers: find what you need with search_code (it gives file:line), then read_file with offset and limit. Prefer your project's own source over vendored or third-party directories.")
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
        const val MAX_MALFORMED = 2
        const val MAX_INTENT_NUDGES = 2
        const val INTENT_MAX_CHARS = 500
        const val INTENT_NUDGE = "Go ahead: make the tool call now to do that, or, if you already have what you need, give your final answer."
        /** A reply that only says what the model is about to do. */
        val INTENT = Regex("""^\s*(let me|let's|i('ll| will)|now,? (let me|i)|next,? (let me|i))\b""", RegexOption.IGNORE_CASE)
        const val OUTPUT_MARGIN_TOKENS = 256
        const val MIN_OUTPUT_TOKENS = 512
        const val RETRY_NUDGE = "Your last reply was cut off. Reply again: first one short sentence about what you are doing, then the tool call."
        /** The tail of a tool call whose opening was lost: a closing tag with nothing opened before it. */
        val LOST_OPENING = Regex("""^(?![\s\S]*<tool_call)[\s\S]*</(arg|tool_call)>""", RegexOption.IGNORE_CASE)
        const val KEEP_RECENT_RESULTS = 3
        const val MIN_KEPT_CHARS = 1_000
        const val BRAKE_PATIENCE = 2
        const val OUTLINE_LINES = 14
        const val OUTLINE_HEAD_LINES = 25
        const val OUTLINE_ENTRY_CHARS = 90
        const val DIGEST_LINES = 8
        const val SHORTENED = "[shortened to save context; call the tool again only if you need the details]"
        val RESULT = Regex("""<tool_result name="([^"]*)"(?: arg="([^"]*)")?>\n(.*?)\n</tool_result>""", RegexOption.DOT_MATCHES_ALL)
        val DECLARATION = Regex("""^\s*((public|private|protected|internal|abstract|open|data|sealed|suspend|override|export|default|async|static|final|inline|actual|expect)\s+)*(class|object|interface|fun|def|function|struct|trait|impl|enum|func|type)\s+\w""")
        /** Tools whose success means work was done (as opposed to looking around). */
        val ACTION_TOOLS = setOf("replace_in_file", "write_file", "create_file", "terminal_run")
        const val EXPLORE_BRAKE = "You have explored enough, and you are going in circles. Stop searching. Choose the file to change, read it once only if you need its exact text for the edit, and make the change with replace_in_file or create_file. If you cannot, give your final answer: what you found and what you would change."
        const val MAX_ACTION_NUDGES = 2
        const val ACTION_NUDGE = "You described the change but did not make it. The user asked you to implement it: do it now. Read the file you will change (once, if you need its exact text), then use replace_in_file or create_file, and summarize when done."
        /** The user is asking for a change to be made (as opposed to a question about the code). */
        val WANTS_CHANGE = Regex("""\b(implement|add|create|fix|change|update|refactor|remove|rename|write|build|make|modify|replace|delete|migrate|support)\b""", RegexOption.IGNORE_CASE)
        val QUESTION = Regex("""^(how|what|why|where|which|who|when|explain|describe|does|do|is|are|can|could|should|would)\b[^\n]*\?\s*$|\?\s*$""", RegexOption.IGNORE_CASE)
        const val FINAL_NUDGE = "Stop using tools now. Give your final answer in plain text: what you found, what should change and where (file paths), and anything you could not do."
        const val GAVE_UP_ANSWER = "I explored the project but could not settle on a change. Tell me which file to start from (for example with @path) and I will continue from there."
        const val MIN_RESULT_CHARS = 1_500
        const val OVERVIEW_MAX_LINES = 40
        const val STUB = "[earlier tool output removed to save context; call the tool again if you need it]"
        val MENTION = Regex("""@\{([^}\s]+)}|(?<![\w@])@([\w./-]*[\w-]\.\w+)""")
    }
}
