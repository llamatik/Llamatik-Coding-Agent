package com.llamatik.agent.core.agent

/**
 * Guesses whether a prompt asks the agent to change files or only to answer a question.
 *
 * The loop uses it to decide whether a prose reply is the answer (a question) or a sign the model
 * failed to make the edit it was asked for (an edit request), which deserves a forced write pass.
 */
object TaskIntent {
    private val EDIT_VERBS = listOf(
        "fix", "add", "change", "update", "modify", "edit", "implement", "refactor", "rewrite", "rename",
        "replace", "remove", "delete", "create", "write", "generate", "make", "convert", "migrate", "patch",
        "improve", "optimize", "optimise", "extract", "move", "insert", "apply", "rework", "port", "document",
    )
    private val EDIT_REGEX = Regex("""\b(${EDIT_VERBS.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

    /** A prompt that opens like a question or an explanation request is a question even if it mentions "fix". */
    private val QUESTION_START = Regex(
        """^\W*(what|why|how|where|which|who|when|is|are|does|do|explain|describe|summari[sz]e|review|analy[sz]e|list|show|find|tell|compare)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PROSE_REQUEST = Regex(
        """\b(story|stories|poem|poems|essay|joke|jokes|haiku|song|lyrics|letter|riddle|limerick|speech|fairy tale|fable)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Anything that ties the request to the project or to code: the agent (with tools) should handle it. */
    private val CODE_CUE = Regex(
        """\b(code|coding|function|method|class|screen|view|component|composable|layout|file|files|folder|project|app|""" +
            """application|module|script|program|bug|test|tests|build|api|ui|git|repo|sql|html|css|json|xml|""" +
            """kotlin|java|swift|python|javascript|typescript|compose|android|ios|gradle|refactor|implement|fix|""" +
            """debug|compile|variable|string resource|database)\b|@|`|\.\w{1,5}\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True for a creative-writing request ("tell me a story about a forest") that has
     * nothing to do with the project. Those get a chat prompt: no tools, no language rules, no code fences.
     * A request that also mentions code ("write a screen that shows a story") stays with the agent.
     */
    fun isConversation(prompt: String): Boolean {
        val text = prompt.trim()
        if (text.isEmpty() || CODE_CUE.containsMatchIn(text)) return false
        return PROSE_REQUEST.containsMatchIn(text)
    }

    fun wantsEdit(prompt: String): Boolean {
        val text = prompt.trim()
        if (QUESTION_START.containsMatchIn(text)) return false
        if (isConversation(text)) return false
        return EDIT_REGEX.containsMatchIn(text)
    }
}
