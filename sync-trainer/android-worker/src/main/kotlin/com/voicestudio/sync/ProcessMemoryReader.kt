package com.voicestudio.sync

import com.voicestudio.edl.FOREGROUND_RAM_CEILING_MB
import java.io.File

/**
 * Reads live resident set size (`VmRSS`) from Linux/Android `/proc/self/status`.
 *
 * Constitutional Law 2: Returns `null` when `/proc/self/status` is absent or unreadable (e.g., on
 * macOS JVM hosts) — NEVER returns a fabricated or estimated memory number.
 */
object ProcessMemoryReader {
    private const val PROC_SELF_STATUS_PATH = "/proc/self/status"
    private val VM_RSS_REGEX = Regex("""(?m)^VmRSS:\s+(\d+)\s+kB\s*$""")

    const val DEFAULT_SAFETY_HEADROOM_MB: Int = 512

    fun readVmRssMb(statusFile: File = File(PROC_SELF_STATUS_PATH)): Int? {
        if (!statusFile.isFile || !statusFile.canRead()) {
            return null
        }
        val content = runCatching { statusFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parseVmRssMb(content)
    }

    fun parseVmRssMb(statusText: String): Int? {
        val match = VM_RSS_REGEX.find(statusText) ?: return null
        val kb = match.groupValues[1].toLongOrNull() ?: return null
        return (kb / 1024L).toInt()
    }

    fun isApproachingCeiling(
        measuredRssMb: Int?,
        ceilingMb: Int = FOREGROUND_RAM_CEILING_MB,
        headroomMb: Int = DEFAULT_SAFETY_HEADROOM_MB
    ): Boolean {
        if (measuredRssMb == null) return false
        val safeLimit = (ceilingMb - headroomMb).coerceAtLeast(256)
        return measuredRssMb >= safeLimit
    }
}
