package com.llamatik.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStreamFilterTest {
    private fun filtered(vararg tokens: String): String {
        val out = StringBuilder()
        val f = AgentStreamFilter { out.append(it) }
        tokens.forEach(f::accept)
        f.finish()
        return out.toString()
    }

    @Test fun `plain text passes through`() = assertEquals("Hello world", filtered("Hello ", "world"))

    @Test fun `think blocks are dropped even when the tags are split`() =
        assertEquals("Answer", filtered("<th", "ink>reason", "ing</thi", "nk>Answer"))

    @Test fun `tool calls are hidden but the words before them stay`() =
        assertEquals("Let me look. ", filtered("Let me look. <tool_", "call name=\"read_file\"><arg name=\"path\">a</arg>", "</tool_call>"))

    @Test fun `control tokens are dropped`() = assertEquals("Done.", filtered("Done.<|im_", "end|>"))

    @Test fun `a lone angle bracket is released at the end`() = assertEquals("a <", filtered("a <"))

    @Test fun `text after a tool call is shown`() = assertEquals("x  y", filtered("x <tool_call name=\"a\"></tool_call> y"))

    @Test fun `the tail of a call whose opening was lost is not shown`() =
        assertEquals("", filtered("model loader", "</arg></tool_call>"))

    @Test fun `a short first word is still released at the end`() = assertEquals("Hi", filtered("Hi"))
}
