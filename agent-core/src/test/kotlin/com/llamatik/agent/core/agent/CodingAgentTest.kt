package com.llamatik.agent.core.agent

import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.testing.FakeBackend
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolResult
import com.llamatik.agent.core.tools.builtin.CodingTools
import com.llamatik.agent.core.tools.builtin.ToolListExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodingAgentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config = BackendConfig("m", 0.7, 512, 4096, 1, true, false, 512, 0, true)

    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    private class Recorder : CodingListener {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        val results = mutableListOf<ToolResult>()
        override fun onText(text: String) { this.text.append(text) }
        override fun onToolCall(call: ToolCall) { calls += call }
        override fun onToolResult(call: ToolCall, result: ToolResult) { results += result }
    }

    private fun agent(backend: FakeBackend): CodingAgent {
        val env = AgentEnvironment.headless(tmp.newFolder().toPath())
        return CodingAgent(backend, env, ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
    }

    @Test
    fun `an unknown tool is reported back to the model instead of ending the run`() {
        val backend = FakeBackend(call("diff_tool", "path" to "x"), "Sorry, done.")
        val rec = Recorder()
        val outcome = agent(backend).run(ChatSession(), "do it", config, rec)
        assertEquals(CodingOutcome.Done, outcome)
        assertTrue(rec.results.single().content.contains("unknown tool 'diff_tool'"))
    }

    @Test
    fun `repeating the same call is cut short`() {
        val repeat = call("search_code", "query" to "zzz")
        val backend = FakeBackend(repeat, repeat, repeat, "Nothing found.")
        val rec = Recorder()
        agent(backend).run(ChatSession(), "find zzz", config, rec)
        assertTrue(rec.results.last().content.contains("already made this exact call"))
    }

    @Test
    fun `an endless tool loop stops at the step limit`() {
        val responses = Array(10) { call("search_code", "query" to "q$it") }
        val env = AgentEnvironment.headless(tmp.newFolder().toPath())
        val agent = CodingAgent(FakeBackend(*responses), env, ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty, CodingLimits(maxSteps = 3))
        assertEquals(CodingOutcome.StepLimit(3), agent.run(ChatSession(), "loop", config, Recorder()))
    }

    @Test
    fun `blank replies are retried then reported`() {
        val outcome = agent(FakeBackend("", "", "")).run(ChatSession(), "hi", config, Recorder())
        assertTrue(outcome is CodingOutcome.Failed)
    }

    @Test
    fun `a mentioned file is attached to the message`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.kt").writeText("class A")
        val env = AgentEnvironment.headless(dir.toPath())
        val backend = FakeBackend("ok")
        val agent = CodingAgent(backend, env, ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        agent.run(ChatSession(), "explain @A.kt", config, Recorder())
        assertTrue(backend.capturedRequests.single().messages.last().content.contains("<file path=\"A.kt\">\nclass A"))
    }

    @Test
    fun `old tool output is shrunk before turns are dropped`() {
        val agent = agent(FakeBackend())
        val big = "x".repeat(6_000)
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "task"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "call 1"),
            ChatMessage(role = ChatRole.TOOL, content = big),
            ChatMessage(role = ChatRole.ASSISTANT, content = "call 2"),
            ChatMessage(role = ChatRole.TOOL, content = big),
            ChatMessage(role = ChatRole.ASSISTANT, content = "call 3"),
            ChatMessage(role = ChatRole.TOOL, content = big),
            ChatMessage(role = ChatRole.ASSISTANT, content = "call 4"),
            ChatMessage(role = ChatRole.TOOL, content = big),
        )
        val fitted = agent.fitHistory(history, "system", config)
        assertEquals(ChatRole.USER, fitted.first().role)
        assertTrue(fitted.sumOf { it.content.length } < history.sumOf { it.content.length })
        assertEquals("the task survives", "task", fitted.first().content)
        assertEquals("newest result is kept whole", big, fitted.last().content)
    }

    @Test
    fun `a call whose opening was lost is asked for again`() {
        val backend = FakeBackend("src/Main.kt</arg></tool_call>", "Let me look. " + call("list_files"), "Done.")
        val rec = Recorder()
        val outcome = agent(backend).run(ChatSession(), "look", config, rec)
        assertEquals(CodingOutcome.Done, outcome)
        assertTrue(backend.capturedRequests[1].messages.last().content.contains("cut off"))
        assertEquals(listOf("list_files"), rec.calls.map { it.name })
    }
}

class EditGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `an edit that unbalances the braces is refused before the user is asked`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.kt").writeText("object A {\n    fun f() = 1\n}\n")
        val env = AgentEnvironment.headless(dir.toPath())
        var asked = false
        val tools = EditApplyingToolExecutor(ToolListExecutor(CodingTools.tools()), env) { asked = true; true }
        val result = tools.execute(
            ToolCall("write_file", mapOf("path" to "A.kt", "content" to "object A {\n    fun f() = 1\n}\n    fun g() = 2\n}\n")),
            com.llamatik.agent.core.tools.ToolExecutionContext(env),
        )
        assertTrue(!result.success && result.content.contains("unbalanced"))
        assertTrue(!asked)
        assertEquals("object A {\n    fun f() = 1\n}\n", java.io.File(dir, "A.kt").readText())
    }
}
