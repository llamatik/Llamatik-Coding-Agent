package com.llamatik.cli

import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolResult

/** How a tool call and its outcome read in the terminal ("Read(src/Main.kt)", "⎿ 120 lines"). */
internal object ToolDisplay {
    private const val ARG_MAX = 80
    private const val SUMMARY_MAX = 140

    fun describe(call: ToolCall): Pair<String, String> {
        val args = call.arguments
        return when (call.name) {
            "read_file" -> "Read" to args["path"].orEmpty()
            "list_files" -> "List" to args["path"].orEmpty().ifBlank { "." }
            "search_code" -> "Search" to args["query"].orEmpty()
            "search_filenames" -> "Find" to args["query"].orEmpty()
            "replace_in_file", "write_file" -> "Update" to args["path"].orEmpty()
            "create_file" -> "Create" to args["path"].orEmpty()
            "terminal_run" -> "Run" to args["command"].orEmpty()
            "git_status" -> "Git status" to ""
            "git_diff" -> "Git diff" to ""
            else -> call.name to args.values.firstOrNull().orEmpty()
        }.let { (label, arg) -> label to arg.lineSequence().firstOrNull().orEmpty().take(ARG_MAX) }
    }

    fun summarize(call: ToolCall, result: ToolResult): String {
        val text = result.content.trim()
        val firstLine = text.lineSequence().firstOrNull().orEmpty().take(SUMMARY_MAX)
        if (!result.success) return firstLine.removePrefix("Error: ").ifBlank { "failed" }
        val lines = if (text.isEmpty()) 0 else text.lines().size
        return when (call.name) {
            "read_file" -> "$lines line${if (lines == 1) "" else "s"}"
            "list_files", "search_code", "search_filenames" -> if (lines == 0) "no results" else "$lines result${if (lines == 1) "" else "s"}"
            "terminal_run" -> text.lineSequence().firstOrNull { it.startsWith("Exit code") }?.substringBefore("  ")?.trim() ?: firstLine
            else -> firstLine.ifBlank { "done" }
        }
    }
}
