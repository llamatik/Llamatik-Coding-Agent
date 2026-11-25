package com.llamatik.cli

import com.llamatik.agent.backends.BackendHandle
import com.llamatik.agent.core.agent.CodingAgent
import com.llamatik.agent.core.agent.CodingListener
import com.llamatik.agent.core.agent.CodingOutcome
import com.llamatik.agent.core.agent.ConfirmingToolExecutor
import com.llamatik.agent.core.agent.EditApplyingToolExecutor
import com.llamatik.agent.core.agent.FilePromptSources
import com.llamatik.agent.core.agent.RunControl
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolResult
import com.llamatik.agent.core.tools.builtin.CodingTools
import com.llamatik.agent.core.tools.builtin.ToolListExecutor
import java.util.concurrent.atomic.AtomicReference

/**
 * The terminal session: reads tasks, runs the shared [AgentLoop] on the chosen backend, shows what the
 * agent does and asks before it changes anything.
 */
class CliApp(
    private val options: CliOptions,
    private val terminal: Terminal,
    private val backendFactory: () -> BackendHandle,
) {
    private val permissions = TerminalPermissionPrompt(terminal, options.yes)
    private val current = AtomicReference<RunControl?>(null)

    /** Stops the task in progress (Ctrl-C). Returns false when nothing was running. */
    fun cancelCurrent(): Boolean {
        val control = current.get() ?: return false
        control.cancel()
        return true
    }

    fun run(): Int {
        if (options.help) { terminal.println(CliArgs.USAGE); return 0 }
        if (options.version) { terminal.println("llamatik $VERSION"); return 0 }

        val backend = try {
            backendFactory()
        } catch (e: Exception) {
            terminal.error(terminal.style(Style.RED, "error: ${e.message}"))
            return 2
        }
        backend.use { handle ->
            val env = AgentEnvironment.headless(options.workDir, permissions)
            val executor = EditApplyingToolExecutor(
                delegate = ConfirmingToolExecutor(ToolListExecutor(CodingTools.tools()), permissions),
                env = env,
                review = ::reviewEdit,
            )
            val agent = CodingAgent(
                backend = if (options.debug) DebugBackend(handle.backend, terminal) else handle.backend,
                env = env,
                tools = executor,
                toolSpecs = CodingTools.specs,
                sources = FilePromptSources(env, "terminal", options.aggressive),
            )
            val session = Session(agent, handle)
            return if (options.prompt != null) {
                if (session.runTask(options.prompt) == Outcome.OK) 0 else 1
            } else {
                interactive(session, handle)
            }
        }
    }

    private enum class Outcome { OK, FAILED, CANCELLED }

    private inner class Session(val agent: CodingAgent, val handle: BackendHandle) {
        var chat = ChatSession()

        fun runTask(prompt: String): Outcome {
            val control = RunControl()
            val view = TerminalView(handle.config.maxTokens.toLong())
            current.set(control)
            val outcome = try {
                agent.run(chat, prompt, handle.config, view, control)
            } finally {
                current.set(null)
                view.finish()
            }
            return when (outcome) {
                CodingOutcome.Done -> Outcome.OK
                CodingOutcome.Cancelled -> {
                    terminal.println(terminal.style(Style.YELLOW, "Cancelled."))
                    Outcome.CANCELLED
                }
                is CodingOutcome.Failed -> {
                    terminal.error(terminal.style(Style.RED, "error: ${outcome.message}"))
                    Outcome.FAILED
                }
                is CodingOutcome.StepLimit -> {
                    terminal.println(terminal.style(Style.YELLOW, "Stopped after ${outcome.steps} steps without finishing. Say \"continue\" to carry on."))
                    Outcome.FAILED
                }
            }
        }
    }

    /** Draws what the agent does: its words as it writes them, each tool it uses, and what came of it. */
    private inner class TerminalView(private val maxTokens: Long) : CodingListener {
        private var progress: Progress = Progress.None
        private var midLine = false
        private var wroteText = false

        override fun onGenerating(step: Int) {
            wroteText = false
            progress = terminal.progress("Working", maxTokens).also { it.update(0) }
        }

        override fun onTokens(count: Int) = progress.update(count.toLong())

        override fun onText(text: String) {
            if (!wroteText) {
                if (text.isBlank()) return
                progress.close() // the live bar gives way to the text
                wroteText = true
                terminal.print(terminal.style(Style.BOLD, "● ") + text.trimStart())
            } else {
                terminal.print(text)
            }
            midLine = !text.endsWith("\n")
        }

        override fun onGenerated(step: Int) {
            progress.close()
            progress = Progress.None
            endLine()
        }

        override fun onToolCall(call: ToolCall) {
            endLine()
            val (label, arg) = ToolDisplay.describe(call)
            terminal.println(terminal.style(Style.CYAN, "● $label") + if (arg.isNotBlank()) terminal.style(Style.DIM, "($arg)") else "")
        }

        override fun onToolResult(call: ToolCall, result: ToolResult) {
            val line = "  ⎿ " + ToolDisplay.summarize(call, result)
            terminal.println(if (result.success) terminal.style(Style.DIM, line) else terminal.style(Style.RED, line))
        }

        override fun onNotice(message: String) {
            endLine()
            terminal.println(terminal.style(Style.DIM, "· $message"))
        }

        private fun endLine() {
            if (midLine) { terminal.println(); midLine = false }
        }

        fun finish() {
            progress.close()
            endLine()
        }
    }

    /** Shows a proposed edit as a diff and asks whether to apply it. */
    private fun reviewEdit(proposal: EditProposal): Boolean {
        terminal.println(colorize(Diff.unified(proposal.filePath, proposal.oldContent, proposal.newContent)))
        return permissions.confirm(PermissionRequest(PermissionKind.EDIT, "Apply this change to ${proposal.filePath}?")) != Decision.DENY
    }

    private fun colorize(diff: String) = diff.lines().joinToString("\n") { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> terminal.style(Style.BOLD, line)
            line.startsWith("+") -> terminal.style(Style.GREEN, line)
            line.startsWith("-") -> terminal.style(Style.RED, line)
            line.startsWith("@@") -> terminal.style(Style.CYAN, line)
            else -> line
        }
    }

    private fun interactive(session: Session, backend: BackendHandle): Int {
        terminal.println(terminal.style(Style.BOLD, "Llamatik Code") + terminal.style(Style.DIM, " — ${backend.description}"))
        terminal.println(terminal.style(Style.DIM, "Project: ${options.workDir}   /help for commands, @path to attach a file, /exit to quit"))
        while (true) {
            val line = terminal.readLine(terminal.style(Style.GREEN, "\n> ")) ?: return 0
            val text = line.trim()
            when {
                text.isEmpty() -> continue
                text == "/exit" || text == "/quit" || text == "exit" || text == "quit" -> return 0
                text == "/help" -> terminal.println(CliArgs.USAGE.substringAfter("In a session: ").let { "In a session: $it" })
                text == "/clear" -> { session.chat = ChatSession(); terminal.println(terminal.style(Style.DIM, "Conversation cleared.")) }
                text == "/yes" -> {
                    permissions.autoApprove = !permissions.autoApprove
                    terminal.println("Auto-approve is ${if (permissions.autoApprove) "ON" else "off"}.")
                }
                text.startsWith("/") -> terminal.println("Unknown command: $text (try /help)")
                else -> session.runTask(text)
            }
        }
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
