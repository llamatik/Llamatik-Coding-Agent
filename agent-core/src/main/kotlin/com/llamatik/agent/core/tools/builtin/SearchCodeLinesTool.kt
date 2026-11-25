package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/**
 * Content search that shows where the text is, like grep: `path:line: text`. A bare list of file names (what
 * [SearchCodeTool] returns) leaves a model guessing which of 40 files matters; the lines usually settle it.
 */
class SearchCodeLinesTool(
    private val maxFiles: Int = 15,
    private val maxLinesPerFile: Int = 3,
    private val maxLineChars: Int = 140,
) : LlamatikTool {
    override val name: String = "search_code"
    override val description: String = "Search project contents for text and show the matching lines. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"]?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult(name, false, "Missing query")
        val files = context.env.files.searchCode(query)
        val out = mutableListOf<String>()
        for (path in files.take(maxFiles)) {
            val lines = runCatching { context.env.files.read(path).lines() }.getOrNull() ?: continue
            var shown = 0
            for ((i, line) in lines.withIndex()) {
                if (!line.contains(query, ignoreCase = true)) continue
                out += "$path:${i + 1}: ${line.trim().take(maxLineChars)}"
                if (++shown >= maxLinesPerFile) break
            }
        }
        if (files.size > maxFiles) out += "… and ${files.size - maxFiles} more files contain it; narrow the query to see them"
        return ToolResult(name, true, out.joinToString("\n"))
    }
}
