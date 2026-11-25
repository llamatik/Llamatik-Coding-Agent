package com.llamatik.agent.backends.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AutoTunerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val gb = 1L shl 30

    private fun mac(ramGb: Long) = Hardware(ramGb * gb, appleSilicon = true, performanceCores = 8, gpuMemoryBytes = (ramGb * gb * 0.75).toLong())
    private fun pc(ramGb: Long) = Hardware(ramGb * gb, appleSilicon = false, performanceCores = 6)

    /** Qwen3-14B shape: 40 layers, 8 KV heads of 128 -> 160 KB per token. */
    private val qwen14b = GgufInfo("qwen3", 40960, 40, 5120, 40, listOf(8), 128, 128)

    @Test
    fun `a big model on a 32 GB Mac gets the context that fits, with both contexts counted`() {
        val s = AutoTuner.tune((14.6 * gb).toLong(), mac(32), qwen14b, maxContext = 32768)
        // Measured: 32K context ran out of GPU memory here (14.6 GB + 2 x (5 GB cache + 0.4 GB buffers) > 25 GB).
        assertTrue("context ${s.contextSize}", s.contextSize!! in 16384..24576)
        assertEquals(-1, s.gpuLayers)
        assertEquals(true, s.flashAttention)
        assertEquals(8, s.threads)
        assertEquals(512, s.batchSize)
        // What the estimate says it uses must stay inside the GPU budget the OS gives (24 GB of 32).
        val used = (14.6 * gb) + 2 * (s.contextSize!! * 163840L + 0.45 * gb) + 0.5 * gb
        assertTrue(used < 24 * gb)
    }

    @Test
    fun `the same model on a 24 GB Mac gets less context because the cache has to fit`() {
        val s = AutoTuner.tune((14.6 * gb).toLong(), mac(24), qwen14b, maxContext = 32768)
        assertTrue("context ${s.contextSize}", s.contextSize!! in 4096 until 8192)
        assertEquals(0, s.contextSize!! % 1024)
    }

    @Test
    fun `without flash attention the attention buffer is charged to the context`() {
        val flash = AutoTuner.tune((14.6 * gb).toLong(), pc(64), qwen14b, maxContext = 131072)
        assertNull(flash.flashAttention)
        // 40 heads x 512 batch x 4 bytes = 80 KB per token on top of the 160 KB of cache, per context.
        val withBigRam = AutoTuner.tune((14.6 * gb).toLong(), pc(64), qwen14b, maxContext = 131072, memoryBudgetBytes = 40 * gb)
        assertTrue(withBigRam.contextSize!! < ((40 * 0.92 - 14.6 - 0.5) * gb / (2 * 163840)).toInt())
    }

    @Test
    fun `a model larger than the memory offloads only some layers`() {
        val s = AutoTuner.tune((14.6 * gb).toLong(), mac(16), qwen14b, maxContext = 32768)
        assertTrue(s.gpuLayers!! in 0 until 40)
        assertEquals(4096, s.contextSize)
        assertTrue(s.notes.any { it.contains("offloading") })
    }

    @Test
    fun `a small model is limited by what it was trained for`() {
        val small = GgufInfo("gemma3", 8192, 26, 1152, 4, listOf(1), 256, 256)
        val s = AutoTuner.tune((0.8 * gb).toLong(), pc(16), small, maxContext = 32768)
        assertEquals(8192, s.contextSize)
        assertNull(s.gpuLayers)
        assertNull(s.flashAttention)
        assertEquals(2048, s.batchSize)
        assertEquals(2048, s.maxTokens)
    }

    @Test
    fun `the user's ceiling and memory budget are honoured`() {
        assertEquals(8192, AutoTuner.tune((14.6 * gb).toLong(), mac(32), qwen14b, maxContext = 8192).contextSize)
        val s = AutoTuner.tune((14.6 * gb).toLong(), mac(32), qwen14b, maxContext = 32768, memoryBudgetBytes = 18 * gb)
        assertTrue(s.contextSize!! < 32768)
    }

    @Test
    fun `without metadata the tuner still returns something sensible`() {
        val s = AutoTuner.tune(8 * gb, pc(16), null, maxContext = 32768)
        assertNotNull(s.contextSize)
        assertTrue(s.notes.any { it.contains("estimated") })
    }

    @Test
    fun `kv cost follows the model shape`() {
        assertEquals(163840L, qwen14b.kvBytesPerToken())
        assertNull(GgufInfo().kvBytesPerToken())
        assertEquals(3L * (64 + 64) * 2, GgufInfo("x", null, 2, null, 4, listOf(1, 2), 64, 64).kvBytesPerToken())
    }

    // ── GGUF reading ──────────────────────────────────────────────────────────

    private class Gguf {
        val out = ByteArrayOutputStream()
        private fun le(n: Int, v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(), 0, n)
        fun str(s: String) { le(8, s.length.toLong()); out.write(s.toByteArray()) }
        fun header(kvCount: Int) { out.write("GGUF".toByteArray()); le(4, 3); le(8, 0); le(8, kvCount.toLong()) }
        fun kvString(k: String, v: String) { str(k); le(4, 8); str(v) }
        fun kvU32(k: String, v: Long) { str(k); le(4, 4); le(4, v) }
        fun kvU32Array(k: String, v: List<Long>) { str(k); le(4, 9); le(4, 4); le(8, v.size.toLong()); v.forEach { le(4, it) } }
        fun kvStringArray(k: String, v: List<String>) { str(k); le(4, 9); le(4, 8); le(8, v.size.toLong()); v.forEach(::str) }
    }

    @Test
    fun `the reader finds the shape and stops before the tokenizer`() {
        val g = Gguf().apply {
            header(9)
            kvString("general.architecture", "llama"); kvString("general.name", "Test")
            kvU32("llama.context_length", 4096); kvU32("llama.block_count", 32); kvU32("llama.embedding_length", 4096)
            kvU32("llama.attention.head_count", 32); kvU32Array("llama.attention.head_count_kv", listOf(8))
            kvStringArray("tokenizer.ggml.tokens", List(1000) { "tok$it" })
            kvU32("llama.never_read", 1)
        }
        val info = GgufReader.parse(g.out.toByteArray().inputStream())!!
        assertEquals(GgufInfo("llama", 4096, 32, 4096, 32, listOf(8)), info)
        assertEquals(32L * 8 * 128 * 2 * 2, info.kvBytesPerToken())
    }

    @Test
    fun `a file that is not a gguf gives null`() {
        val f = tmp.newFile("x.gguf").apply { writeText("not a model") }
        assertNull(GgufReader.read(f))
        assertNull(GgufReader.read(tmp.root.resolve("missing.gguf")))
    }
}
