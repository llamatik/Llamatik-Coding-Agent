package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.agent.ToolSpec

/** The tools [com.llamatik.agent.core.agent.CodingAgent] offers the model: a small set it can use reliably. */
object CodingTools {
    val specs: List<ToolSpec> = listOf(
        ToolSpec("list_files", "List the files under a directory (omit path for the project root).", "path (optional)"),
        ToolSpec("search_code", "Find the files whose contents contain a text (case-insensitive).", "query"),
        ToolSpec("search_filenames", "Find files by all or part of their name.", "query"),
        ToolSpec("read_file", "Read a file.", "path"),
        ToolSpec("replace_in_file", "Edit an existing file by replacing text. Preferred way to change code.", "path, oldText (exact text from the file), newText"),
        ToolSpec("write_file", "Replace a whole file with new content.", "path, content"),
        ToolSpec("create_file", "Create a new file.", "path, content"),
        ToolSpec("git_status", "Show the working tree status."),
        ToolSpec("git_diff", "Show the uncommitted changes."),
        ToolSpec("terminal_run", "Run a build or test command, for example ./gradlew test.", "command"),
    )

    /** Instances of the tools above, for a [ToolListExecutor]. */
    fun tools() = BuiltinTools.all().filter { tool -> specs.any { it.name == tool.name } }
}
