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

class ToolFailureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a tool that throws becomes an error result and the run goes on`() {
        val env = AgentEnvironment.headless(tmp.newFolder().toPath())
        val exploding = object : ToolExecutor {
            override fun execute(call: ToolCall, context: com.llamatik.agent.core.tools.ToolExecutionContext): ToolResult = error("boom")
            override fun listToolNames() = "read_file"
            override fun dynamicToolNames() = emptySet<String>()
        }
        val backend = FakeBackend("Reading. <tool_call name=\"read_file\"><arg name=\"path\">a</arg></tool_call>", "It failed, sorry.")
        val config = BackendConfig("m", 0.7, 512, 4096, 1, true, false, 512, 0, true)
        val outcome = CodingAgent(backend, env, exploding, CodingTools.specs, PromptSources.Empty).run(ChatSession(), "x", config, object : CodingListener {})
        assertEquals(CodingOutcome.Done, outcome)
        assertTrue(backend.capturedRequests[1].messages.last().content.contains("boom"))
    }
}

class StepBudgetTest {
    @get:Rule val tmp = TemporaryFolder()
    private val config = BackendConfig("m", 0.7, 512, 4096, 1, true, false, 512, 0, true)
    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    @Test
    fun `an announcement without a tool call is nudged instead of ending the run`() {
        val backend = FakeBackend("Let me analyze the code to find the best place.", call("list_files"), "Done: nothing to change.")
        val env = AgentEnvironment.headless(tmp.newFolder().toPath())
        val agent = CodingAgent(backend, env, ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        assertEquals(CodingOutcome.Done, agent.run(ChatSession(), "do it", config, object : CodingListener {}))
        assertTrue(backend.capturedRequests[1].messages.last().content.startsWith("Go ahead"))
        assertEquals(3, backend.capturedRequests.size)
    }

    @Test
    fun `calls beyond the per-reply limit are reported as not run`() {
        val dir = tmp.newFolder()
        val backend = FakeBackend("Reading. " + (1..5).joinToString("") { call("search_code", "query" to "q$it") }, "ok, done here.")
        val agent = CodingAgent(backend, AgentEnvironment.headless(dir.toPath()), ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        agent.run(ChatSession(), "x", config, object : CodingListener {})
        val results = backend.capturedRequests[1].messages.last().content
        assertEquals(2, Regex("not run").findAll(results).count())
    }

    @Test
    fun `the newest results survive and a long file says how to continue`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "Big.kt").writeText((1..2000).joinToString("\n") { "val line$it = $it" })
        val backend = FakeBackend("Reading. " + call("read_file", "path" to "Big.kt"), "It is big.")
        val agent = CodingAgent(backend, AgentEnvironment.headless(dir.toPath()), ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        agent.run(ChatSession(), "read Big.kt", config, object : CodingListener {})
        val result = backend.capturedRequests[1].messages.last().content
        assertTrue(result.contains("The file has 2000 lines"))
        assertTrue(result.contains("offset=<line> and limit=<lines>"))
    }

    @Test
    fun `read_file can start at a line`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.txt").writeText("a\nb\nc\nd\n")
        val env = AgentEnvironment.headless(dir.toPath())
        val r = ToolListExecutor(CodingTools.tools()).execute(ToolCall("read_file", mapOf("path" to "A.txt", "offset" to "2", "limit" to "2")), com.llamatik.agent.core.tools.ToolExecutionContext(env))
        assertEquals("b\nc\n[lines 2-3 of 5]", r.content)
    }
}

class ExplorationBrakeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val config = BackendConfig("m", 0.7, 512, 4096, 1, true, false, 512, 0, true)
    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    private fun agent(backend: FakeBackend, dir: java.io.File, limits: CodingLimits) =
        CodingAgent(backend, AgentEnvironment.headless(dir.toPath()), ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty, limits)

    @Test
    fun `an agent that keeps exploring is told to stop and then made to answer`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.kt").writeText("class A\n")
        val backend = FakeBackend(
            "Looking. " + call("search_code", "query" to "a1"),
            "More. " + call("search_code", "query" to "a2"),
            "More. " + call("search_code", "query" to "a3"),
            "More. " + call("search_code", "query" to "a4"),
            "I found that A.kt defines class A and nothing else needs to change. " + call("search_code", "query" to "a5"),
        )
        val session = ChatSession()
        val outcome = agent(backend, dir, CodingLimits(maxExploreSteps = 2)).run(session, "task", config, object : CodingListener {})
        assertEquals(CodingOutcome.Done, outcome)
        assertEquals("I found that A.kt defines class A and nothing else needs to change.", session.messages.last().content)
        // The brake went out with the results of step 2; the forced answer came after two more exploring steps.
        assertTrue(backend.capturedRequests[2].messages.any { it.content.contains("going in circles") })
        val last = backend.capturedRequests.last().messages.last().content
        assertTrue(last.startsWith("Stop using tools now"))
        assertEquals(5, backend.capturedRequests.size)
    }

    @Test
    fun `acting resets the brake`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.kt").writeText("class A\n")
        val backend = FakeBackend(
            call("search_code", "query" to "a1") + "", "Reading. " + call("search_code", "query" to "a2"),
            "Edit. " + call("replace_in_file", "path" to "A.kt", "oldText" to "class A", "newText" to "class B"),
            "Done editing.",
        )
        val env = AgentEnvironment.headless(dir.toPath())
        val tools = EditApplyingToolExecutor(ToolListExecutor(CodingTools.tools()), env) { true }
        val agent = CodingAgent(backend, env, tools, CodingTools.specs, PromptSources.Empty, CodingLimits(maxExploreSteps = 3))
        assertEquals(CodingOutcome.Done, agent.run(ChatSession(), "rename", config, object : CodingListener {}))
        assertEquals("class B\n", java.io.File(dir, "A.kt").readText())
        assertTrue(backend.capturedRequests.none { r -> r.messages.any { it.content.contains("going in circles") } })
    }

    @Test
    fun `a shortened file result keeps its declarations`() {
        val agent = agent(FakeBackend(), tmp.newFolder(), CodingLimits())
        val body = "package x\n\nclass Models {\n    fun pick(): Int = 1\n    private val secret = 2\n}\n" + "// filler\n".repeat(100)
        val shortened = agent.shorten("<tool_result name=\"read_file\" arg=\"src/Models.kt\">\n$body\n</tool_result>")
        assertTrue(shortened.contains("arg=\"src/Models.kt\""))
        assertTrue(shortened.contains("class Models {"))
        assertTrue(shortened.contains("fun pick()"))
        assertTrue(!shortened.contains("filler"))
        assertTrue(shortened.length < 600)
        assertEquals(shortened, agent.shorten(shortened))
    }

    @Test
    fun `search shows matching lines with their numbers`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "A.kt").writeText("class A\nfun loadModel() {}\n// loadModel again\n")
        java.io.File(dir, "B.kt").writeText("nothing\n")
        val env = AgentEnvironment.headless(dir.toPath())
        val r = ToolListExecutor(CodingTools.tools()).execute(ToolCall("search_code", mapOf("query" to "loadmodel")), com.llamatik.agent.core.tools.ToolExecutionContext(env))
        assertEquals("A.kt:2: fun loadModel() {}\nA.kt:3: // loadModel again", r.content)
    }

    @Test
    fun `ignored directories are skipped by search`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "vendor/llama.cpp").mkdirs()
        java.io.File(dir, "vendor/llama.cpp/x.cpp").writeText("loadModel")
        java.io.File(dir, "app.kt").writeText("loadModel")
        val env = AgentEnvironment.headless(dir.toPath(), userIgnoredDirs = { setOf("llama.cpp") })
        assertEquals(listOf("app.kt"), env.files.searchCode("loadModel"))
    }
}

class ActionNudgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val config = BackendConfig("m", 0.7, 512, 4096, 1, true, false, 512, 0, true)
    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    @Test
    fun `a request to implement that ends in a plan is sent back to do it`() {
        val dir = tmp.newFolder().also { java.io.File(it, "A.kt").writeText("class A\n") }
        val env = AgentEnvironment.headless(dir.toPath())
        val backend = FakeBackend(
            "I would rename A to B in A.kt.",
            "Editing. " + call("replace_in_file", "path" to "A.kt", "oldText" to "class A", "newText" to "class B"),
            "Renamed A to B.",
        )
        val tools = EditApplyingToolExecutor(ToolListExecutor(CodingTools.tools()), env) { true }
        val agent = CodingAgent(backend, env, tools, CodingTools.specs, PromptSources.Empty)
        assertEquals(CodingOutcome.Done, agent.run(ChatSession(), "I want to rename class A to B", config, object : CodingListener {}))
        assertEquals("class B\n", java.io.File(dir, "A.kt").readText())
        assertTrue(backend.capturedRequests[1].messages.last().content.startsWith("You described the change"))
    }

    @Test
    fun `a question is answered without being pushed to edit`() {
        val dir = tmp.newFolder()
        val backend = FakeBackend("It is defined in A.kt and does nothing special.")
        val agent = CodingAgent(backend, AgentEnvironment.headless(dir.toPath()), ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        agent.run(ChatSession(), "How would you add logging to this? Where is it defined?", config, object : CodingListener {})
        assertEquals(1, backend.capturedRequests.size)
    }

    @Test
    fun `the nudge is given twice at most`() {
        val dir = tmp.newFolder()
        val backend = FakeBackend("Plan one.", "Plan two.", "Plan three.", "Plan four.")
        val agent = CodingAgent(backend, AgentEnvironment.headless(dir.toPath()), ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
        agent.run(ChatSession(), "implement logging", config, object : CodingListener {})
        assertEquals(3, backend.capturedRequests.size)
    }
}

class LargeFileOutlineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val config = BackendConfig("m", 0.7, 2048, 16384, 1, true, false, 512, 0, true)

    @Test
    fun `a big file comes back as a numbered map and a part can be read by line`() {
        val dir = tmp.newFolder()
        val text = buildString {
            appendLine("package demo"); appendLine("class Big {")
            repeat(400) { i -> appendLine("    fun step$i() { println(\"step $i of the long running process\") }") }
            appendLine("    fun refreshModelState() = 1")
            appendLine("}")
        }
        java.io.File(dir, "Big.kt").writeText(text)
        val env = AgentEnvironment.headless(dir.toPath())
        val backend = FakeBackend("Reading. <tool_call name=\"read_file\"><arg name=\"path\">Big.kt</arg></tool_call>", "Got the map.")
        CodingAgent(backend, env, ToolListExecutor(CodingTools.tools()), CodingTools.specs, PromptSources.Empty)
            .run(ChatSession(), "look at Big.kt", config, object : CodingListener {})
        val result = backend.capturedRequests[1].messages.last().content
        assertTrue(result.contains("L2 class Big {"))
        assertTrue(result.contains("L3 fun step0()"))
        assertTrue(result.length < 9_000)
        // search finds the line, and a part is readable around it.
        val tools = ToolListExecutor(CodingTools.tools())
        val ctx = com.llamatik.agent.core.tools.ToolExecutionContext(env)
        val hit = tools.execute(ToolCall("search_code", mapOf("query" to "refreshModelState")), ctx).content
        assertEquals("Big.kt:403:     fun refreshModelState() = 1".replace(":     ", ": "), hit)
        val part = tools.execute(ToolCall("read_file", mapOf("path" to "Big.kt", "offset" to "402", "limit" to "3")), ctx).content
        assertTrue(part.startsWith("    fun step399()"))
        assertTrue(part.contains("fun refreshModelState()"))
    }
}

class FailedEditHintTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a replacement that does not match shows the real lines`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "V.kt").writeText("class V {\n    fun onDownloadModel(id: String) {\n        repo.save(id)\n    }\n}\n")
        val env = AgentEnvironment.headless(dir.toPath())
        val tools = EditApplyingToolExecutor(ToolListExecutor(CodingTools.tools()), env) { true }
        val r = tools.execute(
            ToolCall("replace_in_file", mapOf("path" to "V.kt", "oldText" to "fun onDownloadModel(id: String) {\n  storage.put(id)\n}", "newText" to "x")),
            com.llamatik.agent.core.tools.ToolExecutionContext(env),
        )
        assertTrue(!r.success)
        assertTrue(r.content.contains("similar line"))
        assertTrue(r.content.contains("        repo.save(id)"))
        val unrelated = tools.execute(
            ToolCall("replace_in_file", mapOf("path" to "V.kt", "oldText" to "completely different text", "newText" to "x")),
            com.llamatik.agent.core.tools.ToolExecutionContext(env),
        )
        assertTrue(unrelated.content.contains("None of the lines"))
    }
}
