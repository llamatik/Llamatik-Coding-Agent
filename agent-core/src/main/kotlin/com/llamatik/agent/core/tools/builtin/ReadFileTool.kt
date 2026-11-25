package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class ReadFileTool : LlamatikTool {
    override val name: String = "read_file"
    override val description: String = "Read a UTF-8 project file. Arguments: path"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val content = context.env.files.read(path)
        val offset = call.arguments["offset"]?.trim()?.toIntOrNull()
        val limit = call.arguments["limit"]?.trim()?.toIntOrNull()
        if (offset == null && limit == null) return ToolResult(name, true, content)
        val lines = content.lines()
        val from = ((offset ?: 1) - 1).coerceIn(0, lines.size)
        val to = if (limit != null && limit > 0) minOf(lines.size, from + limit) else lines.size
        val part = lines.subList(from, to).joinToString("\n")
        val note = if (from > 0 || to < lines.size) "\n[lines ${from + 1}-$to of ${lines.size}]" else ""
        return ToolResult(name, true, part + note)
    }
}
