package com.castla.mirror.diagnostics

/**
 * Parsed output of the post-mortem shell script (see [PostMortem]): the
 * device's recorded boot reason, recent system-level crash records from
 * DropBox, and the phantom-process-killer settings. Each output line carries a
 * prefix identifying its source; unknown lines are ignored.
 */
data class PostMortemReport(
    val props: Map<String, String>,
    val settings: Map<String, String>,
    val uptimeSec: String?,
    val propGrep: List<String>,
    val dropboxEvents: List<String>,
    val details: List<String>,
    val tombstones: List<String>,
    val kmsg: List<String>,
    /** Abort message, crashing thread and top backtrace of the newest native crash. */
    val nativeCrash: List<String> = emptyList()
) {
    fun summaryLines(): List<String> = buildList {
        val reason = props["sys.boot.reason"].orEmpty()
        add("boot reason: ${reason.ifEmpty { "<unreadable>" }} → ${PostMortemParser.classifyBootReason(reason)}")
        props["sys.boot.reason.last"]?.takeIf { it.isNotBlank() }?.let { add("previous boot reason: $it") }
        props["persist.sys.boot.reason.history"]?.takeIf { it.isNotBlank() }?.let { add("boot reason history: $it") }
        propGrep.forEach { add("prop $it") }
        uptimeSec?.let { add("device uptime: ${it}s") }
        add("phantom-process monitor: ${settings["phantom_monitor"].orEmpty().ifEmpty { "<default>" }}, " +
            "max_phantom_processes: ${settings["max_phantom"].orEmpty().ifEmpty { "<default 32>" }}")
        if (dropboxEvents.isEmpty()) {
            add("system crash records (DropBox): none found")
        } else {
            add("system crash records (DropBox, oldest → newest):")
            dropboxEvents.forEach { add("  $it") }
        }
        if (details.isNotEmpty()) {
            add("latest system_server failure detail:")
            details.forEach { add("  $it") }
        }
        if (nativeCrash.isNotEmpty()) {
            add("latest native crash (which thread aborted and why):")
            nativeCrash.forEach { add("  $it") }
        }
        if (tombstones.isNotEmpty()) {
            add("native crash tombstones:")
            tombstones.forEach { add("  $it") }
        }
        if (kmsg.isNotEmpty()) {
            add("last kernel log before the previous reboot (filtered):")
            kmsg.forEach { add("  $it") }
        }
    }
}

object PostMortemParser {

    fun parse(output: String): PostMortemReport {
        val props = LinkedHashMap<String, String>()
        val settings = LinkedHashMap<String, String>()
        var uptime: String? = null
        val propGrep = ArrayList<String>()
        val dropbox = ArrayList<String>()
        val details = ArrayList<String>()
        val tombs = ArrayList<String>()
        val kmsg = ArrayList<String>()
        val native = ArrayList<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("prop.") -> {
                    val kv = line.removePrefix("prop.")
                    val eq = kv.indexOf('=')
                    if (eq > 0) props[kv.substring(0, eq)] = kv.substring(eq + 1).trim()
                }
                line.startsWith("setting.") -> {
                    val kv = line.removePrefix("setting.")
                    val eq = kv.indexOf('=')
                    if (eq > 0) settings[kv.substring(0, eq)] = kv.substring(eq + 1).trim()
                }
                line.startsWith("uptime=") -> uptime = line.removePrefix("uptime=").trim().ifEmpty { null }
                line.startsWith("propgrep=") -> line.removePrefix("propgrep=").trim().takeIf { it.isNotEmpty() }?.let(propGrep::add)
                line.startsWith("dropbox: ") -> dropbox.add(line.removePrefix("dropbox: ").trim())
                line.startsWith("detail.") -> details.add(line.removePrefix("detail.").trim())
                line.startsWith("native: ") -> native.add(line.removePrefix("native: ").trimEnd())
                line.startsWith("tomb: ") -> tombs.add(line.removePrefix("tomb: ").trim())
                line.startsWith("kmsg: ") -> kmsg.add(line.removePrefix("kmsg: ").trim())
            }
        }
        return PostMortemReport(props, settings, uptime, propGrep, dropbox, details, tombs, kmsg, native)
    }

    /**
     * Maps Android's canonical boot reason (`sys.boot.reason`, e.g.
     * "shutdown,thermal", "kernel_panic", "reboot,userrequested") to a cause
     * category. Order matters: "reboot,thermal,battery" is thermal.
     */
    fun classifyBootReason(reason: String): String {
        val r = reason.lowercase().trim()
        return when {
            r.isEmpty() -> "UNKNOWN (property not readable on this device)"
            "thermal" in r -> "THERMAL — the phone overheated and the system shut it down"
            "battery" in r -> "BATTERY — battery too low or unsafe"
            "panic" in r -> "KERNEL_PANIC — kernel/driver crash (e.g. GPU, video encoder, display driver)"
            "watchdog" in r -> "WATCHDOG — the system hung and a watchdog reset it"
            "hw_reset" in r || r.startsWith("hard") -> "HARDWARE_RESET — hardware/PMIC reset (hard hang or power problem)"
            "userrequested" in r || "shell" in r || "adb" in r || "powerkey" in r ||
                "ota" in r || "recovery" in r || "bootloader" in r -> "USER/INTENTIONAL — requested reboot or power key"
            else -> "UNCLASSIFIED ($reason)"
        }
    }
}
