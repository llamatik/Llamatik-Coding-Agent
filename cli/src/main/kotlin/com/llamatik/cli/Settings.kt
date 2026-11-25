package com.llamatik.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.llamatik.agent.backends.InferenceParams
import com.llamatik.agent.core.agent.CodingLimits
import java.io.File
import java.io.StringReader
import java.nio.file.Path

class SettingsException(message: String) : Exception(message)

/** Inference settings a file or a flag may change; null leaves the value as it is. */
data class InferenceOverrides(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextSize: Int? = null,
    val threads: Int? = null,
    val batchSize: Int? = null,
    val gpuLayers: Int? = null,
    val flashAttention: Boolean? = null,
    val useMmap: Boolean? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val repeatPenalty: Double? = null,
) {
    fun applyTo(p: InferenceParams) = p.copy(
        temperature = temperature ?: p.temperature, maxTokens = maxTokens ?: p.maxTokens,
        contextSize = contextSize ?: p.contextSize, threads = threads ?: p.threads,
        batchSize = batchSize ?: p.batchSize, gpuLayers = gpuLayers ?: p.gpuLayers,
        flashAttention = flashAttention ?: p.flashAttention, useMmap = useMmap ?: p.useMmap,
        topP = topP ?: p.topP, topK = topK ?: p.topK, repeatPenalty = repeatPenalty ?: p.repeatPenalty,
    )

    /** [other] wins where it has a value. */
    fun over(other: InferenceOverrides) = InferenceOverrides(
        other.temperature ?: temperature, other.maxTokens ?: maxTokens, other.contextSize ?: contextSize,
        other.threads ?: threads, other.batchSize ?: batchSize, other.gpuLayers ?: gpuLayers,
        other.flashAttention ?: flashAttention, other.useMmap ?: useMmap,
        other.topP ?: topP, other.topK ?: topK, other.repeatPenalty ?: repeatPenalty,
    )
}

/** How the agent loop itself behaves (see [CodingLimits]); null leaves the default. */
data class AgentOverrides(
    val maxSteps: Int? = null,
    val maxToolCallsPerStep: Int? = null,
    val temperature: Double? = null,
    val charsPerToken: Double? = null,
    val thinking: Boolean? = null,
    val maxResultChars: Int? = null,
    val outputReserveShare: Double? = null,
) {
    fun applyTo(l: CodingLimits) = l.copy(
        maxSteps = maxSteps ?: l.maxSteps, maxToolCallsPerStep = maxToolCallsPerStep ?: l.maxToolCallsPerStep,
        temperatureCap = temperature ?: l.temperatureCap, charsPerToken = charsPerToken ?: l.charsPerToken,
        thinking = thinking ?: l.thinking, maxResultChars = maxResultChars ?: l.maxResultChars,
        outputReserveShare = outputReserveShare ?: l.outputReserveShare,
    )

    fun over(other: AgentOverrides) = AgentOverrides(
        other.maxSteps ?: maxSteps, other.maxToolCallsPerStep ?: maxToolCallsPerStep, other.temperature ?: temperature,
        other.charsPerToken ?: charsPerToken, other.thinking ?: thinking, other.maxResultChars ?: maxResultChars,
        other.outputReserveShare ?: outputReserveShare,
    )
}

/** The contents of one settings file (or the merge of several). */
data class Settings(
    val model: String? = null,
    val server: String? = null,
    val autoApprove: Boolean? = null,
    val inference: InferenceOverrides = InferenceOverrides(),
    val agent: AgentOverrides = AgentOverrides(),
) {
    /** [other] wins where it has a value. */
    fun over(other: Settings) = Settings(
        model = other.model ?: model, server = other.server ?: server, autoApprove = other.autoApprove ?: autoApprove,
        inference = inference.over(other.inference), agent = agent.over(other.agent),
    )
}

/** What was read: the merged [settings], the files that contributed, and things worth telling the user. */
data class LoadedSettings(val settings: Settings, val files: List<String>, val warnings: List<String>)

/**
 * Settings files, in the spirit of Claude Code's: later ones win.
 *  1. `~/.llamatik/settings.json`            — yours, for every project
 *  2. `<project>/.llamatik/settings.json`    — shared with the project (commit it)
 *  3. `<project>/.llamatik/settings.local.json` — yours, for this project only (keep it out of git)
 * Command-line flags win over all of them. Files are JSON; `//` and `#` comments are allowed.
 */
object SettingsLoader {
    fun load(home: Path, project: Path, extra: Path? = null): LoadedSettings {
        val candidates = listOfNotNull(
            home.resolve(".llamatik/settings.json"),
            project.resolve(".llamatik/settings.json"),
            project.resolve(".llamatik/settings.local.json"),
            extra,
        )
        var merged = Settings()
        val files = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (path in candidates) {
            val file = path.toFile()
            if (!file.isFile) {
                if (path == extra) throw SettingsException("Settings file not found: $path")
                continue
            }
            val parsed = parse(file.readText(), file.path)
            merged = merged.over(parsed.first)
            files += file.path
            warnings += parsed.second
        }
        return LoadedSettings(merged, files, warnings)
    }

    /** Parses one file's text; returns the settings and the warnings (unknown keys). [source] names the file in errors. */
    fun parse(text: String, source: String): Pair<Settings, List<String>> {
        val root = try {
            JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true })
        } catch (e: Exception) {
            throw SettingsException("$source: not valid JSON (${e.message?.lineSequence()?.firstOrNull()})")
        }
        if (root.isJsonNull) return Settings() to emptyList()
        if (!root.isJsonObject) throw SettingsException("$source: expected a JSON object at the top level")
        return Reader(root.asJsonObject, source).read()
    }

    private class Reader(private val root: JsonObject, private val source: String) {
        private val warnings = mutableListOf<String>()

        fun read(): Pair<Settings, List<String>> {
            val inference = section("inference")
            val agent = section("agent")
            val settings = Settings(
                model = string(root, "model"),
                server = string(root, "server"),
                autoApprove = bool(section("permissions"), "permissions", "autoApprove"),
                inference = InferenceOverrides(
                    temperature = double(inference, "inference", "temperature", 0.0..2.0),
                    maxTokens = int(inference, "inference", "maxTokens", 1..Int.MAX_VALUE),
                    contextSize = int(inference, "inference", "contextSize", 512..Int.MAX_VALUE),
                    threads = int(inference, "inference", "threads", 1..4096),
                    batchSize = int(inference, "inference", "batchSize", 64..Int.MAX_VALUE),
                    gpuLayers = int(inference, "inference", "gpuLayers", -1..Int.MAX_VALUE),
                    flashAttention = bool(inference, "inference", "flashAttention"),
                    useMmap = bool(inference, "inference", "useMmap"),
                    topP = double(inference, "inference", "topP", 0.0..1.0),
                    topK = int(inference, "inference", "topK", 0..Int.MAX_VALUE),
                    repeatPenalty = double(inference, "inference", "repeatPenalty", 0.5..3.0),
                ),
                agent = AgentOverrides(
                    maxSteps = int(agent, "agent", "maxSteps", 1..1000),
                    maxToolCallsPerStep = int(agent, "agent", "maxToolCallsPerStep", 1..20),
                    temperature = double(agent, "agent", "temperature", 0.0..2.0),
                    charsPerToken = double(agent, "agent", "charsPerToken", 1.0..8.0),
                    thinking = bool(agent, "agent", "thinking"),
                    maxResultChars = int(agent, "agent", "maxResultChars", 1_000..1_000_000),
                    outputReserveShare = double(agent, "agent", "outputReserveShare", 0.05..0.9),
                ),
            )
            warnUnknown(root, "", setOf("model", "server", "permissions", "inference", "agent"))
            return settings to warnings
        }

        private fun section(name: String): JsonObject? {
            val e = root.get(name) ?: return null
            if (e.isJsonNull) return null
            if (!e.isJsonObject) throw SettingsException("$source: \"$name\" must be an object")
            return e.asJsonObject
        }

        private val seen = mutableMapOf<JsonObject, MutableSet<String>>()

        private fun element(obj: JsonObject?, path: String, key: String): JsonElement? {
            if (obj == null) return null
            seen.getOrPut(obj) { mutableSetOf() } += key
            return obj.get(key)?.takeUnless { it.isJsonNull }
        }

        private fun primitive(e: JsonElement, path: String, key: String, what: String): JsonPrimitive {
            if (!e.isJsonPrimitive) throw SettingsException("$source: \"$path.$key\" must be $what")
            return e.asJsonPrimitive
        }

        private fun string(obj: JsonObject?, key: String): String? {
            val e = element(obj, "", key) ?: return null
            val p = primitive(e, "", key, "a string")
            if (!p.isString) throw SettingsException("$source: \"$key\" must be a string")
            return p.asString.takeIf { it.isNotBlank() }?.let(::expandHome)
        }

        private fun bool(obj: JsonObject?, path: String, key: String): Boolean? {
            val e = element(obj, path, key) ?: return null
            val p = primitive(e, path, key, "true or false")
            if (!p.isBoolean) throw SettingsException("$source: \"$path.$key\" must be true or false")
            return p.asBoolean
        }

        private fun double(obj: JsonObject?, path: String, key: String, range: ClosedFloatingPointRange<Double>): Double? {
            val e = element(obj, path, key) ?: return null
            val p = primitive(e, path, key, "a number")
            if (!p.isNumber) throw SettingsException("$source: \"$path.$key\" must be a number")
            val v = p.asDouble
            if (v !in range) throw SettingsException("$source: \"$path.$key\" must be between ${range.start} and ${range.endInclusive} (got $v)")
            return v
        }

        private fun int(obj: JsonObject?, path: String, key: String, range: IntRange): Int? {
            val e = element(obj, path, key) ?: return null
            val p = primitive(e, path, key, "a whole number")
            val v = if (p.isNumber) p.asBigDecimal.let { if (it.stripTrailingZeros().scale() <= 0) it.toLong() else null } else null
            if (v == null) throw SettingsException("$source: \"$path.$key\" must be a whole number")
            if (v !in range.first.toLong()..range.last.toLong()) {
                val max = if (range.last == Int.MAX_VALUE) "" else " and ${range.last}"
                throw SettingsException("$source: \"$path.$key\" must be ${if (max.isEmpty()) "at least ${range.first}" else "between ${range.first}$max"} (got $v)")
            }
            return v.toInt()
        }

        private fun warnUnknown(obj: JsonObject, prefix: String, known: Set<String>) {
            for (key in obj.keySet()) {
                if (key !in known) warnings += "$source: unknown setting \"$prefix$key\" ignored"
            }
            for (name in listOf("inference", "agent", "permissions")) {
                if (prefix.isNotEmpty()) break
                val child = obj.get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                warnUnknown(child, "$name.", seen[child].orEmpty())
            }
        }

        private fun expandHome(path: String) =
            if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
    }

    /** Where `/config` and the docs point people at. */
    fun userFile(home: Path): File = home.resolve(".llamatik/settings.json").toFile()
}
