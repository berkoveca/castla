package com.castla.mirror.diagnostics

/**
 * Parsers for /proc files, used by the health heartbeat to tell "phone
 * overloaded" (high load average, Castla burning CPU, thread/fd leaks) apart
 * from a system_server bug.
 */
object ProcStats {

    /** Kernel USER_HZ on Android (arm64). */
    const val CLOCK_TICKS_PER_SEC = 100

    /** `/proc/loadavg` → "1m/5m/15m". */
    fun loadAvg(text: String): String? {
        val f = text.trim().split(Regex("\\s+"))
        return if (f.size >= 3 && f[0].isNotEmpty()) "${f[0]}/${f[1]}/${f[2]}" else null
    }

    /** `Threads:` from `/proc/<pid>/status`. */
    fun threads(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("Threads:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    /** utime + stime (clock ticks) from `/proc/<pid>/stat`; the comm field may contain spaces and ')'. */
    fun cpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val f = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // f[0] = state (field 3); utime = field 14 → f[11], stime = field 15 → f[12]
        val utime = f.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = f.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** CPU use in % of one core between two samples. */
    fun cpuPercent(prevTicks: Long, nowTicks: Long, elapsedMs: Long): Int {
        if (elapsedMs <= 0 || nowTicks < prevTicks) return 0
        val cpuMs = (nowTicks - prevTicks) * 1000L / CLOCK_TICKS_PER_SEC
        return (cpuMs * 100 / elapsedMs).toInt()
    }
}
