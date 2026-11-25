package com.llamatik.agent.core.agent

/**
 * Turns the raw token stream of one model reply into the text a person should read: `<think>` blocks and
 * `<tool_call>` blocks are swallowed (the agent reports tool calls itself) and model control tokens are
 * dropped. A tag split across tokens is held back until it can be recognised.
 */
class AgentStreamFilter(private val onText: (String) -> Unit) {
    private enum class State { TEXT, THINK, CALL }

    private val buf = StringBuilder()
    private var state = State.TEXT
    private var emittedAny = false

    fun accept(token: String) {
        buf.append(token)
        drain(final = false)
    }

    /** The reply is complete: release whatever was held back. */
    fun finish() = drain(final = true)

    private fun drain(final: Boolean) {
        while (buf.isNotEmpty()) {
            val text = buf.toString()
            when (state) {
                State.TEXT -> {
                    val open = firstOf(text, listOf(THINK_OPEN, TOOL_CALL_OPEN) + CONTROL_TOKENS)
                    if (open != null) {
                        val (idx, tag) = open
                        emit(text.substring(0, idx))
                        buf.delete(0, idx + (if (tag == TOOL_CALL_OPEN) 0 else tag.length))
                        when (tag) {
                            THINK_OPEN -> state = State.THINK
                            TOOL_CALL_OPEN -> { buf.delete(0, TOOL_CALL_OPEN.length); state = State.CALL }
                        }
                        continue
                    }
                    // The local runtime can drop the opening of a reply that starts with a tag, leaving the tail of a
                    // tool call ("query</arg></tool_call>"). Never show that debris.
                    if (!emittedAny) {
                        val orphanEnd = ORPHAN_CLOSERS.maxOf { c -> text.lastIndexOf(c, ignoreCase = true).let { if (it < 0) -1 else it + c.length } }
                        if (orphanEnd > 0) { buf.delete(0, orphanEnd); continue }
                        // Hold the first words back until we know they are not debris.
                        if (!final && '\n' !in text && text.length < FIRST_LINE_HOLD) return
                    }
                    val hold = if (final) 0 else partialTagSuffix(text, listOf(THINK_OPEN, TOOL_CALL_OPEN) + CONTROL_TOKENS)
                    emit(text.substring(0, text.length - hold))
                    buf.delete(0, text.length - hold)
                    return
                }
                State.THINK -> {
                    val close = text.indexOf(THINK_CLOSE, ignoreCase = true)
                    if (close >= 0) {
                        buf.delete(0, close + THINK_CLOSE.length)
                        state = State.TEXT
                        continue
                    }
                    discardAllBut(text, THINK_CLOSE, final)
                    return
                }
                State.CALL -> {
                    val close = text.indexOf(TOOL_CALL_CLOSE, ignoreCase = true)
                    if (close >= 0) {
                        buf.delete(0, close + TOOL_CALL_CLOSE.length)
                        state = State.TEXT
                        continue
                    }
                    discardAllBut(text, TOOL_CALL_CLOSE, final)
                    return
                }
            }
        }
    }

    private fun discardAllBut(text: String, closer: String, final: Boolean) {
        val hold = if (final) 0 else partialTagSuffix(text, listOf(closer))
        buf.delete(0, text.length - hold)
    }

    private fun emit(text: String) {
        if (text.isBlank() && !emittedAny) return
        if (text.isNotEmpty()) { emittedAny = true; onText(text) }
    }

    private fun firstOf(text: String, tags: List<String>): Pair<Int, String>? =
        tags.mapNotNull { tag -> text.indexOf(tag, ignoreCase = true).takeIf { it >= 0 }?.let { it to tag } }.minByOrNull { it.first }

    /** How many trailing characters of [text] could be the beginning of one of [tags]. */
    private fun partialTagSuffix(text: String, tags: List<String>): Int {
        val longest = tags.maxOf { it.length } - 1
        for (len in minOf(longest, text.length) downTo 1) {
            val tail = text.takeLast(len)
            if (tags.any { it.startsWith(tail, ignoreCase = true) }) return len
        }
        return 0
    }

    private companion object {
        const val TOOL_CALL_OPEN = "<tool_call"
        const val TOOL_CALL_CLOSE = "</tool_call>"
        const val FIRST_LINE_HOLD = 40
        val ORPHAN_CLOSERS = listOf("</arg>", TOOL_CALL_CLOSE)
        val CONTROL_TOKENS = listOf("<|im_end|>", "<|endoftext|>", "<|end|>", "<end_of_turn>", "<eos>")
    }
}
