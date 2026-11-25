package com.llamatik.agent.backends.auto

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What a GGUF model file says about its own shape; enough to size its memory use. Any field may be unknown. */
data class GgufInfo(
    val architecture: String? = null,
    /** Context length the model was trained for. */
    val trainedContext: Int? = null,
    val blockCount: Int? = null,
    val embeddingLength: Int? = null,
    val headCount: Int? = null,
    /** Key/value heads per layer (one entry when all layers share the same count). */
    val headCountKv: List<Int> = emptyList(),
    val keyLength: Int? = null,
    val valueLength: Int? = null,
) {
    /** Bytes of KV cache per token of context (f16 keys and values), or null when the file does not say enough. */
    fun kvBytesPerToken(): Long? {
        val layers = blockCount ?: return null
        val kv = headCountKv.takeIf { it.isNotEmpty() } ?: headCount?.let { listOf(it) } ?: return null
        val perHead = headDim() ?: return null
        val kvHeadsTotal = if (kv.size == 1) kv[0].toLong() * layers else kv.sumOf { it.toLong() }
        // key + value, 2 bytes each (f16)
        return kvHeadsTotal * (keyLength ?: perHead) * 2 + kvHeadsTotal * (valueLength ?: perHead) * 2
    }

    private fun headDim(): Int? = keyLength ?: if (embeddingLength != null && headCount != null && headCount > 0) embeddingLength / headCount else null
}

/** Reads the metadata header of a GGUF file (not the tensors, so it is fast even for huge models). */
object GgufReader {
    fun read(file: File): GgufInfo? = try {
        BufferedInputStream(file.inputStream(), 1 shl 16).use { parse(it) }
    } catch (_: Exception) {
        null // not a GGUF file, truncated, or unreadable: the tuner falls back to estimates
    }

    internal fun parse(input: InputStream): GgufInfo? {
        val r = LittleEndian(input)
        if (r.bytes(4).toString(Charsets.US_ASCII) != "GGUF") return null
        r.u32() // version
        r.u64() // tensor count
        val kvCount = r.u64()
        var arch: String? = null
        val numbers = HashMap<String, List<Long>>()
        for (i in 0 until kvCount) {
            val key = r.string()
            // Everything we need precedes the tokenizer (which holds huge arrays), so stop there.
            if (key.startsWith("tokenizer.")) break
            val type = r.u32().toInt()
            val value = readValue(r, type)
            when {
                key == "general.architecture" -> arch = value as? String
                value is List<*> && value.all { it is Long } -> numbers[key] = value.map { it as Long }
            }
        }
        fun num(suffix: String): Long? = arch?.let { numbers["$it.$suffix"]?.firstOrNull() }
        return GgufInfo(
            architecture = arch,
            trainedContext = num("context_length")?.toInt(),
            blockCount = num("block_count")?.toInt(),
            embeddingLength = num("embedding_length")?.toInt(),
            headCount = num("attention.head_count")?.toInt(),
            headCountKv = arch?.let { numbers["$it.attention.head_count_kv"] }?.map { it.toInt() } ?: emptyList(),
            keyLength = num("attention.key_length")?.toInt(),
            valueLength = num("attention.value_length")?.toInt(),
        )
    }

    /** A value as a String, or a list of Long for integers (scalars become one-element lists); others are skipped. */
    private fun readValue(r: LittleEndian, type: Int): Any? = when (type) {
        0, 1 -> listOf(r.bytes(1)[0].toLong())
        2, 3 -> listOf(r.bytes(2).let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toLong() })
        4, 5 -> listOf(r.u32())
        6 -> { r.bytes(4); null }
        7 -> { r.bytes(1); null }
        8 -> r.string()
        9 -> {
            val elementType = r.u32().toInt()
            val n = r.u64()
            val keep = n <= 4096 && elementType in setOf(0, 1, 2, 3, 4, 5, 10, 11)
            val out = ArrayList<Long>()
            for (i in 0 until n) {
                val v = readValue(r, elementType)
                if (keep && v is List<*>) out += v.map { it as Long }
            }
            if (keep) out else null
        }
        10, 11 -> listOf(r.u64())
        12 -> { r.bytes(8); null }
        else -> throw IllegalArgumentException("unknown GGUF value type $type")
    }

    private class LittleEndian(private val input: InputStream) {
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var read = 0
            while (read < n) {
                val c = input.read(b, read, n - read)
                if (c < 0) throw EOFException()
                read += c
            }
            return b
        }

        fun u32(): Long = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        fun u64(): Long = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long

        fun string(): String {
            val len = u64()
            require(len in 0..(1 shl 24)) { "implausible string length $len" }
            return String(bytes(len.toInt()), Charsets.UTF_8)
        }
    }
}
