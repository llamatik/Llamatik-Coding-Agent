package com.llamatik.cli

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.PrintStream
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

    /** A live status line for one model request; a no-op when output is not a terminal. */
    fun progress(label: String): Progress = Progress.None
}

/**
 * Live status of one model request. First the model reads the prompt ([context]), then it writes: [update]
 * receives the number of tokens generated so far. [pause] runs [block] (which prints) without garbling the line.
 */
interface Progress : AutoCloseable {
    /** Estimated size of the prompt being read, in tokens. */
    fun context(promptTokens: Long) {}
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

    override fun progress(label: String): Progress =
        if (interactive && color) SpinnerProgress(out, label) else Progress.None

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
 * One live line: a spinner, what the model is doing, token count, speed and elapsed time, redrawn ten times a
 * second from its own thread. The speed counts from the first generated token, so reading a long prompt does not
 * drag it down. [pause] and [close] erase the line first, so whatever is printed next starts clean.
 */
private class SpinnerProgress(private val out: PrintStream, private val label: String) : Progress {
    private val started = System.nanoTime()
    @Volatile private var promptTokens = 0L
    @Volatile private var generated = 0L
    @Volatile private var firstTokenAt = 0L
    @Volatile private var running = true
    private val lock = Any()
    private val ticker = Thread(::loop, "llamatik-status").apply { isDaemon = true }

    init { ticker.start() }

    override fun context(promptTokens: Long) { this.promptTokens = promptTokens }

    override fun update(done: Long) {
        if (done > 0 && firstTokenAt == 0L) firstTokenAt = System.nanoTime()
        generated = done
    }

    override fun pause(block: () -> Unit) {
        erase()
        block()
    }

    override fun close() {
        running = false
        runCatching { ticker.join(500) }
        erase()
    }

    private fun erase() = synchronized(lock) { out.print("\r\u001B[2K"); out.flush() }

    private fun loop() {
        var frame = 0
        while (running) {
            synchronized(lock) {
                if (running) { out.print("\r\u001B[2K" + render(FRAMES[frame++ % FRAMES.size])); out.flush() }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    private fun render(spinner: String): String {
        val now = System.nanoTime()
        val elapsed = (now - started) / 1_000_000_000
        val time = "%d:%02d".format(java.util.Locale.ROOT, elapsed / 60, elapsed % 60)
        val detail = if (generated == 0L) {
            if (promptTokens > 0) "reading ${compact(promptTokens)} tokens of context" else "thinking"
        } else {
            val seconds = (now - firstTokenAt) / 1e9
            val speed = if (seconds >= 0.5) " · %.1f tok/s".format(java.util.Locale.ROOT, generated / seconds) else ""
            "writing · $generated tokens$speed"
        }
        return "\u001B[36m$spinner\u001B[0m $label · $detail · \u001B[2m$time\u001B[0m"
    }

    private fun compact(n: Long) = if (n >= 1000) "%.1fK".format(java.util.Locale.ROOT, n / 1000.0) else n.toString()

    private companion object {
        val FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    }
}
