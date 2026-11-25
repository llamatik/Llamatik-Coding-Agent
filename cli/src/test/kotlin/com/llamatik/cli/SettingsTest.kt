package com.llamatik.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, text: String) = File(dir, ".llamatik/$name").apply { parentFile.mkdirs(); writeText(text) }

    private fun fail(text: String): String = try {
        SettingsLoader.parse(text, "f.json"); error("expected a SettingsException")
    } catch (e: SettingsException) { e.message!! }

    @Test
    fun `every option of the app can be set`() {
        val (s, warnings) = SettingsLoader.parse("""
            {
              "model": "/m/qwen.gguf",
              "inference": { "temperature": 0.2, "maxTokens": 4096, "contextSize": 32768, "batchSize": 1024, "topP": 0.9, "topK": 20,
                             "repeatPenalty": 1.05, "threads": 6, "gpuLayers": 20, "flashAttention": true, "useMmap": false },
              "agent": { "maxSteps": 12, "maxToolCallsPerStep": 2, "temperature": 0.1, "charsPerToken": 3.0, "thinking": true,
                         "maxResultChars": 8000, "outputReserveShare": 0.3 },
              "permissions": { "autoApprove": true }
            }""", "f.json")
        assertTrue(warnings.isEmpty())
        val p = s.inference.applyTo(com.llamatik.agent.backends.InferenceParams())
        assertEquals(32768, p.contextSize); assertEquals(0.9, p.topP, 0.0); assertEquals(20, p.topK); assertEquals(1.05, p.repeatPenalty, 0.0)
        assertTrue(p.flashAttention); assertFalse(p.useMmap); assertEquals(20, p.gpuLayers)
        val l = s.agent.applyTo(com.llamatik.agent.core.agent.CodingLimits())
        assertEquals(12, l.maxSteps); assertTrue(l.thinking); assertEquals(3.0, l.charsPerToken, 0.0)
        assertEquals(true, s.autoApprove)
    }

    @Test
    fun `comments and an empty file are fine`() {
        assertNull(SettingsLoader.parse("// my defaults\n{ \"model\": \"~/m.gguf\" # local\n}", "f.json").first.model?.takeIf { it.startsWith("~") })
        assertEquals(Settings(), SettingsLoader.parse("", "f.json").first)
    }

    @Test
    fun `later files win and unset values fall through`() {
        val home = tmp.newFolder("home"); val project = tmp.newFolder("project")
        write(home, "settings.json", """{ "inference": { "contextSize": 8192, "temperature": 0.5 }, "model": "/m/a.gguf" }""")
        write(project, "settings.json", """{ "inference": { "contextSize": 32768 } }""")
        write(project, "settings.local.json", """{ "inference": { "temperature": 0.1 } }""")
        val loaded = SettingsLoader.load(home.toPath(), project.toPath())
        assertEquals(3, loaded.files.size)
        assertEquals(32768, loaded.settings.inference.contextSize)
        assertEquals(0.1, loaded.settings.inference.temperature!!, 0.0)
        assertEquals("/m/a.gguf", loaded.settings.model)
    }

    @Test
    fun `flags beat files and files beat defaults`() {
        val home = tmp.newFolder("home"); val project = tmp.newFolder("project")
        write(project, "settings.json", """{ "inference": { "contextSize": 32768, "topK": 10 }, "agent": { "maxSteps": 7 } }""")
        val options = CliArgs.parse(listOf("--context", "4096", "-C", project.path))
        val merged = options.withSettings(SettingsLoader.load(home.toPath(), project.toPath()))
        assertEquals(4096, merged.params.contextSize)
        assertEquals(10, merged.params.topK)
        assertEquals(0.95, merged.params.topP, 0.0)
        assertEquals(7, merged.limits.maxSteps)
        assertEquals(30, CliArgs.parse(listOf("--max-steps", "30")).withSettings(LoadedSettings(Settings(), emptyList(), emptyList())).limits.maxSteps)
    }

    @Test
    fun `a model flag replaces the model of the files and both cannot be combined`() {
        val loaded = LoadedSettings(Settings(model = "/m/from-file.gguf"), emptyList(), emptyList())
        assertEquals("/m/flag.gguf", CliArgs.parse(listOf("-m", "/m/flag.gguf")).withSettings(loaded).model)
        assertEquals("/m/from-file.gguf", CliArgs.parse(emptyList()).withSettings(loaded).model)
        val both = LoadedSettings(Settings(model = "/m/a.gguf", server = "host"), emptyList(), emptyList())
        try { CliArgs.parse(emptyList()).withSettings(both); error("expected failure") } catch (_: SettingsException) {}
    }

    @Test
    fun `wrong values name the file and the key`() {
        assertTrue(fail("""{ "inference": { "temperature": "hot" } }""").contains("\"inference.temperature\" must be a number"))
        assertTrue(fail("""{ "inference": { "contextSize": 100 } }""").contains("\"inference.contextSize\""))
        assertTrue(fail("""{ "inference": { "topK": 1.5 } }""").contains("whole number"))
        assertTrue(fail("""{ "agent": { "thinking": "yes" } }""").contains("true or false"))
        assertTrue(fail("""[1, 2]""").contains("JSON object"))
        assertTrue(fail("""{ broken""").contains("not valid JSON"))
        assertTrue(fail("""{ "inference": 3 }""").contains("must be an object"))
    }

    @Test
    fun `unknown keys are warned about, not fatal`() {
        val (_, warnings) = SettingsLoader.parse("""{ "modle": "x", "inference": { "contextSise": 1 } }""", "f.json")
        assertTrue(warnings.any { it.contains("\"modle\"") })
        assertTrue(warnings.any { it.contains("\"inference.contextSise\"") })
    }

    @Test
    fun `an explicit settings file that is missing is an error`() {
        try { SettingsLoader.load(tmp.newFolder("h").toPath(), tmp.newFolder("p").toPath(), File(tmp.root, "nope.json").toPath()); error("expected failure") }
        catch (e: SettingsException) { assertTrue(e.message!!.contains("not found")) }
    }
}
