package com.castla.mirror.diagnostics

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects system-level evidence about the previous reboot through the Shizuku
 * shell (uid 2000 can read what an app cannot):
 *
 *  - the recorded boot reason (`sys.boot.reason`, e.g. "shutdown,thermal",
 *    "kernel_panic") and vendor reset-reason properties,
 *  - DropBox crash records: system_server crashes/watchdogs (framework
 *    "soft reboot"), SYSTEM_RESTART, native tombstones (e.g. surfaceflinger),
 *    SYSTEM_LAST_KMSG (kernel log from before the reboot),
 *  - the phantom-process-killer settings that decide whether cloudflared (a
 *    child process of this app) may be killed.
 *
 * Runs at most once per app process, on its own thread, right after the
 * privileged service connects. Output is bounded (head/tail) and stderr is
 * discarded — PrivilegedService.execCommand turns certain stderr text into
 * exceptions.
 */
object PostMortem {

    private const val TAG = "PostMortem"
    private val started = AtomicBoolean(false)

    @Volatile var lastReport: PostMortemReport? = null
        private set

    private val SCRIPT = """
p() { printf 'prop.%s=%s\n' "${'$'}1" "${'$'}(getprop "${'$'}1" 2>/dev/null | tr '\n' ' ')"; }
p sys.boot.reason
p sys.boot.reason.last
p persist.sys.boot.reason
p persist.sys.boot.reason.history
getprop 2>/dev/null | grep -iE 'bootreason|reset_reason|rst_stat|pwron|pwroff|poweroff_reason|powerup_reason' | grep -v 'sys.boot.reason' | head -n 10 | sed 's/^/propgrep=/'
printf 'setting.phantom_monitor=%s\n' "${'$'}(settings get global settings_enable_monitor_phantom_procs 2>/dev/null)"
printf 'setting.max_phantom=%s\n' "${'$'}(device_config get activity_manager max_phantom_processes 2>/dev/null)"
printf 'uptime=%s\n' "${'$'}(cut -d' ' -f1 /proc/uptime 2>/dev/null)"
dumpsys dropbox 2>/dev/null | grep -E '^[0-9][0-9-]* [0-9:]* (system_server_[a-z_]*|SYSTEM_RESTART|SYSTEM_TOMBSTONE|SYSTEM_LAST_KMSG|SYSTEM_BOOT|SYSTEM_FSCK|SYSTEM_RECOVERY_LOG|system_app_native_crash|system_app_crash|data_app_native_crash|data_app_crash|data_app_anr|[a-z_]*thermal[a-z_]*) ' | tail -n 25 | sed 's/^/dropbox: /'
for T in system_server_crash system_server_watchdog system_server_native_crash; do
  dumpsys dropbox --print ${'$'}T 2>/dev/null | sed -n '/^=====/h;/^=====/!H;${'$'}{x;/^=====/p;}' | grep -v '^=====' | grep -v '^ *${'$'}' | head -n 16 | sed "s/^/detail.${'$'}T: /"
done
dumpsys dropbox --print SYSTEM_TOMBSTONE 2>/dev/null | grep -E '^(Timestamp:|Cmdline:|signal |Abort message:)' | tail -n 16 | sed 's/^/tomb: /'
dumpsys dropbox --print SYSTEM_LAST_KMSG 2>/dev/null | sed -n '/^=====/h;/^=====/!H;${'$'}{x;/^=====/p;}' | grep -iE 'panic|oops|bug:|watchdog|thermal|overheat|shutdown|reboot|oom-kill|out of memory' | tail -n 20 | sed 's/^/kmsg: /'
true
"""

    /**
     * Runs the collection once per process. [exec] must run a shell command as
     * the Shizuku shell user and return stdout (or null on failure).
     */
    fun collectOnce(exec: (String) -> String?) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                val t0 = System.currentTimeMillis()
                val out = exec(SCRIPT.trimIndent())
                if (out == null) {
                    FileLogger.w(TAG, "post-mortem collection failed (no output from privileged shell)")
                    started.set(false) // allow a retry on the next connect
                    return@Thread
                }
                val report = PostMortemParser.parse(out)
                lastReport = report
                FileLogger.i(TAG, "collected in ${System.currentTimeMillis() - t0}ms")
                report.summaryLines().forEach { FileLogger.i(TAG, it) }
            } catch (t: Throwable) {
                Log.w(TAG, "post-mortem collection failed", t)
                FileLogger.w(TAG, "post-mortem collection threw ${t.javaClass.simpleName}: ${t.message}")
                started.set(false)
            }
        }, "diag-postmortem").apply { isDaemon = true }.start()
    }

    fun summaryLines(): List<String> =
        lastReport?.summaryLines()
            ?: listOf("(not collected — needs the Shizuku privileged service to have connected in this app run)")
}
