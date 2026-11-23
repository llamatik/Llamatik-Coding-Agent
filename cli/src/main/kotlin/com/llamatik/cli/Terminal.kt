package com.llamatik.cli

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.PrintStream
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.animation.progress.update
import com.github.ajalt.mordant.widgets.progress.completed
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarLayout
import com.github.ajalt.mordant.widgets.progress.speed
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeElapsed
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.TerminalBuilder

/** The user's terminal; an interface so the whole session can be scripted in tests. */
interface Terminal {
    /** True when a person can answer questions (stdin is a terminal). */
    val interactive: Boolean
    fun print(text: String)
    fun println(text: String = "")
    fun error(text: String)
    /** Prompts and reads one line; null at end of input. */
    fun readLine(prompt: String): String?
    /** Like [readLine]; [remember] = false keeps the answer out of the input history (y/N questions). */
    fun readLine(prompt: String, remember: Boolean): String? = readLine(prompt)
    fun style(kind: Style, text: String): String

    /** A table as printable text; plain aligned columns unless the terminal can draw boxes. */
    fun table(headers: List<String>, rows: List<List<String>>): String = plainTable(headers, rows)

    /** A live progress line for work of [total] steps (tokens); a no-op when output is not a terminal. */
    fun progress(label: String, total: Long): Progress = Progress.None
}

/** Live progress of one task. [pause] runs [block] (which prints) without garbling the bar. */
interface Progress : AutoCloseable {
    fun update(done: Long)
    fun pause(block: () -> Unit)
    override fun close()

    object None : Progress {
        override fun update(done: Long) {}
        override fun pause(block: () -> Unit) = block()
        override fun close() {}
    }
}

internal fun plainTable(headers: List<String>, rows: List<List<String>>): String {
    val all = listOf(headers) + rows
    val widths = headers.indices.map { c -> all.maxOf { it.getOrElse(c) { "" }.length } }
    return all.joinToString("\n") { row ->
        row.mapIndexed { c, cell -> cell.padEnd(widths[c]) }.joinToString("  ").trimEnd()
    }
}

enum class Style { DIM, BOLD, RED, GREEN, CYAN, YELLOW }

class SystemTerminal(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    override val interactive: Boolean = System.console() != null,
    private val historyFile: File = File(System.getProperty("user.home"), ".llamatik/cli_history"),
    private val color: Boolean = System.console() != null && System.getenv("NO_COLOR") == null,
) : Terminal {
    override fun print(text: String) { out.print(text); out.flush() }
    override fun println(text: String) { out.println(text); out.flush() }
    override fun error(text: String) { err.println(text); err.flush() }

    override fun readLine(prompt: String): String? = readLine(prompt, remember = true)

    override fun readLine(prompt: String, remember: Boolean): String? {
        val reader = if (remember) lineReader else answerReader
        if (reader == null) {
            print(prompt)
            return input.readLine()
        }
        return try {
            reader.readLine(prompt)
        } catch (_: UserInterruptException) {
            null // Ctrl-C at the prompt ends the session, as before
        } catch (_: EndOfFileException) {
            null // Ctrl-D
        }
    }

    private val jlineTerminal: org.jline.terminal.Terminal? by lazy {
        if (!interactive) return@lazy null
        try { TerminalBuilder.builder().system(true).dumb(true).build() } catch (_: Throwable) { null }
    }

    /** JLine editing (arrows, history, Ctrl-R) when a person is typing; plain stdin when piped or scripted. */
    private val lineReader: LineReader? by lazy {
        val jline = jlineTerminal ?: return@lazy null
        historyFile.parentFile?.mkdirs()
        try {
            LineReaderBuilder.builder()
                .terminal(jline)
                .history(DefaultHistory())
                .variable(LineReader.HISTORY_FILE, historyFile)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true) // "!" is ordinary text in prompts
                .build()
        } catch (_: Throwable) { null }
    }

    /** Same terminal, no history: for y/N questions. */
    private val answerReader: LineReader? by lazy {
        val jline = jlineTerminal ?: return@lazy null
        try { LineReaderBuilder.builder().terminal(jline).build() } catch (_: Throwable) { null }
    }

    private val mordant: com.github.ajalt.mordant.terminal.Terminal by lazy { com.github.ajalt.mordant.terminal.Terminal() }

    override fun table(headers: List<String>, rows: List<List<String>>): String {
        if (!color) return plainTable(headers, rows)
        return try {
            mordant.render(com.github.ajalt.mordant.table.table {
                header { row(*headers.toTypedArray()) }
                body { rows.forEach { row(*it.toTypedArray()) } }
            }).toString()
        } catch (_: Throwable) {
            plainTable(headers, rows)
        }
    }

    override fun progress(label: String, total: Long): Progress {
        if (!interactive || !color || total <= 0) return Progress.None
        return try {
            MordantProgress(mordant, label, total)
        } catch (_: Throwable) {
            Progress.None
        }
    }

    override fun style(kind: Style, text: String): String {
        if (!color) return text
        val code = when (kind) {
            Style.DIM -> "2"; Style.BOLD -> "1"; Style.RED -> "31"; Style.GREEN -> "32"
            Style.CYAN -> "36"; Style.YELLOW -> "33"
        }
        return "\u001B[${code}m$text\u001B[0m"
    }
}

/**
 * Spinner, bar, count and speed on one live line, drawn by Mordant. The animation runs on its own thread,
 * so [pause] tears it down while [block] prints and starts a fresh one afterwards (keeping the count).
 */
private class MordantProgress(
    private val terminal: com.github.ajalt.mordant.terminal.Terminal,
    private val label: String,
    private val total: Long,
) : Progress {
    private var animator: com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator<Unit>? = null
    private var done = 0L
    private var closed = false

    private fun start() {
        val layout = com.github.ajalt.mordant.widgets.progress.progressBarLayout {
            spinner(com.github.ajalt.mordant.widgets.Spinner.Dots())
            text(label)
            progressBar(width = 24)
            completed(suffix = " tokens")
            speed(suffix = " tok/s")
            timeElapsed()
        }
        animator = layout.animateOnThread(terminal, total = total, completed = done, clearWhenFinished = true)
            .also { it.execute() }
    }

    @Synchronized override fun update(done: Long) {
        if (closed) return
        this.done = done.coerceAtMost(total)
        if (animator == null) start()
        animator?.update(this.done)
    }

    @Synchronized override fun pause(block: () -> Unit) {
        stopAnimation()
        block()
    }

    @Synchronized override fun close() {
        closed = true
        stopAnimation()
    }

    private fun stopAnimation() {
        animator?.clear()
        animator = null
    }
}
