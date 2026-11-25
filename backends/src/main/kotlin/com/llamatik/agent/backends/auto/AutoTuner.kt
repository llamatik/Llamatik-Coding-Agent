package com.llamatik.agent.backends.auto

import java.io.File

/** Settings the tuner proposes; null means "no opinion, keep the default". [notes] explain them to the user. */
data class Suggestion(
    val contextSize: Int? = null,
    val threads: Int? = null,
    val gpuLayers: Int? = null,
    val batchSize: Int? = null,
    val maxTokens: Int? = null,
    val flashAttention: Boolean? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Picks inference settings that fit the machine and the model, instead of one fixed set for everybody.
 *
 * The main question is how much context fits. Memory use, measured on llama.cpp with Metal, is
 *
 *     weights + CONTEXTS x (KV cache + compute buffer) + overhead
 *
 * where the KV cache grows with the context (see [GgufInfo.kvBytesPerToken]) and the compute buffer is small
 * with flash attention but grows with context x batch x heads without it. [CONTEXTS] is 2 because the runtime
 * keeps one idle generation context next to the session's own; lower it if that ever changes.
 */
object AutoTuner {
    private const val GB = 1L shl 30
    private const val MB = 1L shl 20
    private const val MIN_CONTEXT = 4096
    const val DEFAULT_MAX_CONTEXT = 32768
    const val CONTEXTS = 2
    /** The JVM, the native runtime and everything else the model file size does not include. */
    private const val FIXED_OVERHEAD = 512 * MB
    /** Compute buffer with flash attention, at batch 512 (measured: 369 MiB GPU + 72 MiB CPU). */
    private const val FLASH_COMPUTE_AT_512 = 450 * MB
    /** Compute buffer without flash attention that does not depend on the context. */
    private const val PLAIN_COMPUTE_BASE = 150 * MB
    /** Of the budget, keep this share free: other programs and estimation error. */
    private const val SAFETY = 0.92

    fun tune(
        modelFile: File,
        hardware: Hardware = Hardware.detect(),
        gguf: GgufInfo? = GgufReader.read(modelFile),
        maxContext: Int = DEFAULT_MAX_CONTEXT,
        memoryBudgetBytes: Long? = null,
    ): Suggestion = tune(modelFile.length(), hardware, gguf, maxContext, memoryBudgetBytes)

    fun tune(modelBytes: Long, hardware: Hardware, gguf: GgufInfo?, maxContext: Int, memoryBudgetBytes: Long? = null): Suggestion {
        val notes = mutableListOf<String>()
        val budget = ((memoryBudgetBytes ?: defaultBudget(hardware)) * SAFETY).toLong()

        // Cost of one token of context. Unknown shape: assume a mid-size model (about 160 KB/token at 16 GB of weights).
        val kvKnown = gguf?.kvBytesPerToken()
        val kvPerToken = kvKnown ?: (160L * 1024 * modelBytes / (16 * GB)).coerceAtLeast(32L * 1024)
        val heads = gguf?.headCount ?: 32
        // Metal runs flash attention well and it removes the big compute buffer; elsewhere keep the safe default.
        val flash = hardware.appleSilicon

        // How much of the model the GPU can take (Apple Silicon): all of it when the weights and a minimal context fit.
        var gpuLayers: Int? = null
        var weights = modelBytes
        if (hardware.appleSilicon) {
            val needsAtLeast = modelBytes + CONTEXTS * (kvPerToken * MIN_CONTEXT + computeBytes(MIN_CONTEXT, 512, heads, flash)) + FIXED_OVERHEAD
            if (needsAtLeast <= budget) {
                gpuLayers = -1
            } else {
                val layers = gguf?.blockCount
                val room = budget - FIXED_OVERHEAD - CONTEXTS * (kvPerToken * MIN_CONTEXT + computeBytes(MIN_CONTEXT, 512, heads, flash))
                if (layers != null && room > 0) {
                    gpuLayers = (layers * room.toDouble() / modelBytes).toInt().coerceIn(0, layers)
                    weights = room
                    notes += "the model is larger than the memory budget: offloading $gpuLayers of $layers layers to the GPU, the rest runs on the CPU"
                } else {
                    notes += "the model may not fit in memory (${gb(modelBytes)} GB of weights, ${gb(budget)} GB budget)"
                }
            }
        }

        val ceiling = minOf(maxContext, gguf?.trainedContext ?: maxContext).coerceAtLeast(MIN_CONTEXT)
        // Prefer the fast batch when it still allows all the context we want; otherwise trade it for context.
        val bigBatch = maxContextFor(budget, weights, kvPerToken, 2048, heads, flash)
        val batch = if (bigBatch >= ceiling) 2048 else 512
        val fits = if (batch == 2048) bigBatch else maxContextFor(budget, weights, kvPerToken, 512, heads, flash)
        val context = (minOf(fits, ceiling) / 1024 * 1024).coerceAtLeast(MIN_CONTEXT)
        if (fits < MIN_CONTEXT) notes += "very little memory is left after the model: context limited to $MIN_CONTEXT tokens, expect slowness or failures"

        val maxTokens = minOf(8192, context / 4).coerceAtLeast(512)
        notes += "context ${context / 1024}K tokens (memory budget ${gb(budget)} GB, model ${gb(modelBytes)} GB, " +
            (if (kvKnown != null) "${kvPerToken / 1024} KB of cache per token" else "~${kvPerToken / 1024} KB of cache per token, estimated") +
            (gguf?.trainedContext?.let { ", trained for ${it / 1024}K" } ?: "") + ")"
        notes += "threads ${hardware.performanceCores}, batch $batch" + (if (gpuLayers == -1) ", all layers on the GPU" else "") + (if (flash) ", flash attention" else "")
        return Suggestion(
            contextSize = context, threads = hardware.performanceCores, gpuLayers = gpuLayers, batchSize = batch,
            maxTokens = maxTokens, flashAttention = if (flash) true else null, notes = notes,
        )
    }

    /** Largest context whose memory use stays within [budget]; 0 when not even the weights fit. */
    private fun maxContextFor(budget: Long, weights: Long, kvPerToken: Long, batch: Int, heads: Int, flash: Boolean): Int {
        val constant = weights + FIXED_OVERHEAD + CONTEXTS * computeBytes(0, batch, heads, flash)
        val perToken = CONTEXTS * (kvPerToken + if (flash) 0 else batch.toLong() * heads * 4)
        return ((budget - constant) / perToken).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Compute buffer of one context. Without flash attention the attention scores (f32) scale with context x batch x heads. */
    private fun computeBytes(context: Int, batch: Int, heads: Int, flash: Boolean): Long =
        if (flash) FLASH_COMPUTE_AT_512 * batch / 512 else PLAIN_COMPUTE_BASE + context.toLong() * batch * heads * 4

    /** Memory a model may use: the GPU's share on Apple Silicon, otherwise most of the RAM (weights are memory-mapped). */
    private fun defaultBudget(hw: Hardware): Long = hw.gpuMemoryBytes ?: (hw.totalMemoryBytes * 0.7).toLong()

    private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / GB.toDouble())
}
