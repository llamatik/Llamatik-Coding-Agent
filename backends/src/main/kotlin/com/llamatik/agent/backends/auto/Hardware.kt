package com.llamatik.agent.backends.auto

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** What this machine can offer a model. */
data class Hardware(
    val totalMemoryBytes: Long,
    /** Apple Silicon: CPU and GPU share one pool of memory and Metal runs the model. */
    val appleSilicon: Boolean,
    /** Cores worth running inference threads on (performance cores where the CPU has both kinds). */
    val performanceCores: Int,
    /** Memory the GPU may use for a model, when the OS limits it (Apple's wired limit); null when not applicable. */
    val gpuMemoryBytes: Long? = null,
) {
    companion object {
        private const val GB = 1L shl 30

        /** Reads the current machine. Anything that cannot be read falls back to a cautious value. */
        fun detect(): Hardware {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val apple = os.contains("mac") && (arch == "aarch64" || arch == "arm64")
            val total = totalMemory() ?: (8 * GB)
            val logical = Runtime.getRuntime().availableProcessors()
            val cores = if (os.contains("mac")) sysctl("hw.perflevel0.physicalcpu")?.toIntOrNull() ?: (logical / 2) else logical / 2
            val gpu = if (apple) appleGpuBudget(total) else null
            return Hardware(total, apple, cores.coerceAtLeast(1), gpu)
        }

        private fun totalMemory(): Long? = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
        }.getOrNull()?.takeIf { it > 0 }

        /** The user's `iogpu.wired_limit_mb` if set, otherwise what macOS allows by default (about 3/4 of RAM on big machines). */
        private fun appleGpuBudget(total: Long): Long {
            val limitMb = sysctl("iogpu.wired_limit_mb")?.toLongOrNull() ?: 0L
            if (limitMb > 0) return limitMb * (1L shl 20)
            return (total * if (total >= 32 * GB) 0.75 else 0.65).toLong()
        }

        private fun sysctl(name: String): String? = runCatching {
            val p = ProcessBuilder("sysctl", "-n", name).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            text.takeIf { p.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()
    }
}
