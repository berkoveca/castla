package com.castla.mirror.network

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.castla.mirror.diagnostics.CloudflaredLogFilter
import com.castla.mirror.diagnostics.DiagnosticNames
import com.castla.mirror.diagnostics.ElfInfo
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.diagnostics.HealthMonitor
import com.castla.mirror.diagnostics.PersistBudget
import com.castla.mirror.diagnostics.ProcessExitDecoder
import com.castla.mirror.policy.TunnelRestartPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Manages a Cloudflare named tunnel exposing the local MirrorServer (port 9090).
 *
 * Named tunnel only: `cloudflared tunnel run --token ...` → the permanent
 * hostname the user configured in the Cloudflare Zero Trust dashboard.
 *
 * The ephemeral "quick tunnel" mode (`cloudflared tunnel --url ...`, a random
 * `*.trycloudflare.com` hostname assigned fresh on every process start) has been
 * removed: every auto-restart of a quick tunnel hands out a NEW hostname, which
 * permanently drops whatever client (the Tesla browser) was connected through the
 * old one — there is no way for it to discover the new URL on its own. A named
 * tunnel's hostname is stable across restarts, so a reconnect is invisible to the
 * client instead of a hard disconnect. Requires [TunnelSecurityConfig.hasNamedTunnel]
 * and `namedTunnelEnabled` to both be true — see [start].
 *
 * The binary is shipped INSIDE the APK as `jniLibs/arm64-v8a/libcloudflared.so`
 * and extracted by the package manager to `nativeLibraryDir` at install time.
 * That directory is SELinux `exec_type` — the ONLY writable location Android
 * 10+ permits `execve()` from (the app's own `filesDir` is `app_data_file`
 * and is blocked by the W^X policy: "error=13, Permission denied").
 */
class CloudflareTunnelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        /** Tag for persisted raw cloudflared lines (kept distinct from our own lifecycle lines). */
        private const val CFD_TAG = "cloudflared"
        // Name the binary lands under once the CI job copies it into
        // app/src/main/jniLibs/<abi>/ ; the package manager extracts any
        // `jniLibs/<abi>/lib*.so` into nativeLibraryDir at install time.
        private const val LIB_NAME = "libcloudflared.so"
        // Timing / retry numbers live in [TunnelRestartPolicy] so they can be
        // unit-tested without spawning a process.

        /**
         * App-wide singleton so a running cloudflared process survives mirroring
         * session boundaries. Without this, every session start would spawn a
         * fresh cloudflared process and force an unnecessary reconnect cycle
         * even though the named tunnel's hostname never actually changes.
         */
        @Volatile private var instance: CloudflareTunnelManager? = null

        fun getInstance(context: Context): CloudflareTunnelManager =
            instance ?: synchronized(this) {
                instance ?: CloudflareTunnelManager(context.applicationContext).also { instance = it }
            }
    }

    private val scopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in tunnel coroutine", throwable)
        FileLogger.e(TAG, "Uncaught exception in tunnel coroutine", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.IO + Job() + scopeExceptionHandler)
    // Serializes start/stop/restart and Process ownership so a stop() (typically
    // invoked from a background coroutine, e.g. the idle-timeout watchdog) can
    // never race with start()/auto-restart and destroy the wrong Process or
    // corrupt tunnel state — which previously closed the cloudflared session from
    // a thread other than the one that started it.
    private val lifecycleLock = java.util.concurrent.locks.ReentrantLock()
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var downloadJob: Job? = null
    private var startTimeoutJob: Job? = null
    @Volatile private var registered = false
    private var lastLocalPort: Int = 9090
    private var restartCount: Int = 0
    private var initialStartRetries: Int = 0
    private var restartJob: Job? = null
    @Volatile private var intentionalStop = false
    // Bumped on every process destroy so a stale stdout-reader cannot schedule
    // a second cloudflared after a newer generation has already started.
    @Volatile private var processGeneration: Int = 0
    private val dropTimestampsMs = ArrayList<Long>()

    // --- Diagnostics (persisted via FileLogger; surfaced in the clipboard report) ---
    @Volatile private var processStartedAtMs = 0L
    @Volatile private var startRequestedAtMs = 0L
    @Volatile private var lastExitSummary: String? = null
    @Volatile private var totalExits = 0
    @Volatile private var binaryInfoLogged = false
    /** Caps persisted cloudflared lines: burst of 40, then one per 15s (a flapping edge can spam WRN). */
    private val persistBudget = PersistBudget(capacity = 40, refillIntervalMs = 15_000L)

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** The extracted `libcloudflared.so` inside nativeLibraryDir (exec-able). */
    private fun binaryFile(): File = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)

    fun isBinaryDownloaded(): Boolean {
        val f = binaryFile()
        return f.exists() && f.length() > 0 && f.canExecute()
    }

    /**
     * Start the named cloudflared tunnel pointing at the given local port.
     * Requires a Cloudflare Zero Trust connector token AND the "permanent
     * tunnel" toggle to be on (see [TunnelSecurityConfig]); fails fast with a
     * clear error otherwise rather than silently falling back to an ephemeral
     * quick tunnel (that fallback has been removed — see the class doc above).
     */
    fun start(localPort: Int = 9090) {
        val config = TunnelSecurityConfig.load(context)
        if (!TunnelSecurityConfig.hasNamedTunnel(config)) {
            Log.e(TAG, "No Cloudflare connector token configured — cannot start tunnel")
            FileLogger.e(TAG, "No Cloudflare connector token configured — cannot start tunnel")
            _error.value = "No Cloudflare tunnel token configured. Add one in Settings."
            return
        }
        if (!config.namedTunnelEnabled) {
            Log.i(TAG, "Named tunnel toggle is off — not starting a tunnel")
            FileLogger.i(TAG, "Named tunnel toggle is off — not starting a tunnel")
            _error.value = null
            return
        }
        Log.i(TAG, "Tunnel mode=NAMED(stable) urlConfigured=${config.namedTunnelUrl.isNotBlank()}")
        lifecycleLock.lock()
        try {
            if (process?.isAlive == true && (_isRunning.value || _isStarting.value)) {
                Log.i(TAG, "Tunnel already running/starting — reusing existing tunnel")
                FileLogger.i(TAG, "start(port=$localPort): reusing running tunnel (${stateLabel()})")
                return
            }
            if (process != null) {
                Log.w(TAG, "Destroying leftover cloudflared before a new start")
                FileLogger.w(TAG, "Destroying leftover cloudflared before a new start (alive=${process?.isAlive})")
                destroyProcessLocked()
            }
            startRequestedAtMs = SystemClock.elapsedRealtime()
            FileLogger.i(TAG, "start(port=$localPort) mode=NAMED urlConfigured=${config.namedTunnelUrl.isNotBlank()} " +
                "restartCount=$restartCount initialRetries=$initialStartRetries")

            intentionalStop = false
            lastLocalPort = localPort
            restartJob?.cancel()
            registered = false
            _isStarting.value = true
            _error.value = null
        } finally {
            lifecycleLock.unlock()
        }

        // Safety net: never leave the UI on "Starting tunnel…" forever.
        startTimeoutJob?.cancel()
        startTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(TunnelRestartPolicy.START_TIMEOUT_MS)
            if (_isStarting.value && !_isRunning.value) {
                Log.w(TAG, "Tunnel start timed out after ${TunnelRestartPolicy.START_TIMEOUT_MS}ms")
                FileLogger.w(TAG, "Tunnel start timed out after ${TunnelRestartPolicy.START_TIMEOUT_MS}ms " +
                    "(cloudflared never logged 'Registered tunnel connection'; alive=${process?.isAlive})")
                if (restartCount == 0 && !registered) {
                    _isStarting.value = false
                    scheduleInitialStartRetry(
                        "cloudflared did not report a tunnel within ${TunnelRestartPolicy.START_TIMEOUT_MS / 1000}s"
                    )
                } else {
                    Log.w(TAG, "Tunnel still (re)starting after timeout - leaving auto-restart to recover")
                }
            }
        }

        downloadJob = scope.launch {
            // Outside the lock: spawning `--version` can take a moment on first run.
            logBinaryInfoOnce()
            lifecycleLock.lock()
            try {
                if (intentionalStop) {
                    Log.i(TAG, "Tunnel start aborted — stop requested during startup")
                    _isStarting.value = false
                    return@launch
                }
                // Clean up the binary a previous build downloaded to filesDir:
                // it can never be executed under Android 10+ W^X anyway.
                runCatching { File(context.filesDir, "cloudflared").delete() }
                if (!isBinaryDownloaded()) {
                    throw IllegalStateException(
                        "cloudflared binary missing from nativeLibraryDir " +
                            "(${binaryFile().absolutePath}). Reinstall the latest APK."
                    )
                }
                startNamedTunnelProcess(config.namedTunnelToken)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                FileLogger.e(TAG, "Failed to start tunnel", e)
                _error.value = e.message ?: "Unknown error"
                _isStarting.value = false
                if (!intentionalStop) {
                    scheduleInitialStartRetry(e.message ?: "start failed")
                }
            } finally {
                lifecycleLock.unlock()
            }
        }
    }

    fun stop(reason: String = "unspecified") {
        lifecycleLock.lock()
        try {
            Log.i(TAG, "Stopping tunnel: $reason")
            FileLogger.i(TAG, "Stopping tunnel: reason=$reason (${stateLabel()})")
            intentionalStop = true
            restartJob?.cancel()
            downloadJob?.cancel()
            startTimeoutJob?.cancel()
            registered = false
            initialStartRetries = 0
            restartCount = 0
            dropTimestampsMs.clear()
            readerThread?.interrupt()
            readerThread = null
            destroyProcessLocked()
            _tunnelUrl.value = null
            _isRunning.value = false
            _isStarting.value = false
            _error.value = null
        } finally {
            lifecycleLock.unlock()
        }
    }

    /**
     * Caller MUST hold [lifecycleLock]. Kills any live cloudflared and invalidates
     * the current stdout-reader generation so a stale thread cannot spawn a twin.
     */
    private fun destroyProcessLocked() {
        val p = process
        process = null
        processGeneration++
        if (p == null) return
        if (p.isAlive) {
            FileLogger.i(TAG, "Destroying live cloudflared (ran ${uptimeLabel()})")
        }
        try {
            p.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy cloudflared", e)
        }
        try {
            if (p.isAlive) p.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to forcibly destroy cloudflared", e)
        }
    }

    private fun processExitCode(proc: Process): Int = try {
        if (proc.isAlive) -1 else proc.exitValue()
    } catch (_: Exception) { -1 }

    /**
     * cloudflared writes its real errors to stderr. We ship a bionic-linked build
     * (from Termux's package) so DNS resolves through Android's netd like any app,
     * but capture the stderr tail anyway so failures are explained instead of an
     * opaque "exited without establishing a tunnel".
     */
    private val stderrTail = java.util.LinkedList<String>()

    private fun collectStderr(proc: Process, gen: Int) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (gen != processGeneration) return@Thread
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared[err]: $l")
                    val redacted = CloudflaredLogFilter.redact(l)
                    synchronized(stderrTail) {
                        stderrTail.add(redacted)
                        while (stderrTail.size > 40) stderrTail.removeFirst()
                    }
                    persistCloudflaredLine(redacted)
                    onTunnelLine("stderr", l)
                }
            } catch (e: Exception) {
                Log.d(TAG, "cloudflared stderr collector ended")
            }
        }, "cloudflared-stderr").also { it.isDaemon = true; it.start() }
    }

    private fun currentStderr(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    /** Persists the lines that explain drops (WRN/ERR, connect/disconnect), rate-limited. */
    private fun persistCloudflaredLine(line: String) {
        if (!CloudflaredLogFilter.shouldPersist(line)) return
        val (ok, suppressed) = synchronized(persistBudget) {
            val acquired = persistBudget.tryAcquire(SystemClock.elapsedRealtime())
            acquired to (if (acquired) persistBudget.takeSuppressedCount() else 0)
        }
        if (!ok) return
        val note = if (suppressed > 0) " [+$suppressed similar lines suppressed]" else ""
        if (CloudflaredLogFilter.isErrorLevel(line)) {
            FileLogger.w(CFD_TAG, line + note)
        } else {
            FileLogger.i(CFD_TAG, line + note)
        }
    }

    /**
     * Logs once per app process what binary we are about to run: size, whether
     * it is a bionic (Android/Termux) or static (upstream Linux) build, and its
     * self-reported version. A static build does its own DNS and cannot read
     * /etc/resolv.conf on Android — the most common "tunnel never connects" cause.
     */
    private fun logBinaryInfoOnce() {
        if (binaryInfoLogged) return
        binaryInfoLogged = true
        try {
            val f = binaryFile()
            if (!f.exists()) {
                FileLogger.e(TAG, "cloudflared binary missing at ${f.absolutePath}")
                return
            }
            val head = f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                val n = input.read(buf).coerceAtLeast(0)
                buf.copyOf(n)
            }
            val version = try {
                val p = ProcessBuilder(f.absolutePath, "--version").redirectErrorStream(true).start()
                val finished = p.waitFor(3, TimeUnit.SECONDS)
                val out = if (finished) p.inputStream.bufferedReader().readText().trim() else "timeout"
                if (!finished) p.destroyForcibly()
                out.lineSequence().firstOrNull().orEmpty().take(160)
            } catch (t: Throwable) {
                "failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            FileLogger.i(TAG, "cloudflared binary: size=${f.length() / 1024}KB exec=${f.canExecute()} " +
                "build=${ElfInfo.describe(head)} version=\"$version\"")
        } catch (t: Throwable) {
            FileLogger.w(TAG, "cloudflared binary inspection failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun clearStderr() = synchronized(stderrTail) { stderrTail.clear() }

    /** Named-tunnel-only: only the "Registered tunnel connection" line matters now. */
    private fun onTunnelLine(stream: String, l: String) {
        if (!registered &&
            (l.contains("Registered tunnel connection") || l.contains("Registered tunnel network"))
        ) {
            registered = true
            restartCount = 0
            initialStartRetries = 0
            restartJob?.cancel()
            Log.i(TAG, "Named tunnel connection registered ($stream)")
            val sinceStart = if (startRequestedAtMs > 0) SystemClock.elapsedRealtime() - startRequestedAtMs else -1
            FileLogger.i(TAG, "Tunnel registered ${sinceStart}ms after start request")
            _isRunning.value = true
            _isStarting.value = false
        }
    }

    private fun failureMessage(fallback: String): String {
        val lastStderr = currentStderr()
        val proc = process
        val exitCode = if (proc != null) processExitCode(proc) else -1
        val meaningful = lastStderr.asReversed().firstOrNull {
            it.trim().isNotEmpty() && !it.contains(" INF ")
        }
        val base = if (meaningful != null) {
            val cleaned = meaningful.substringAfter("ERR ").trim().ifBlank { meaningful.trim() }
            "cloudflared: $cleaned (exit $exitCode)"
        } else {
            fallback + " (exit $exitCode)"
        }
        val lower = lastStderr.joinToString(" ").lowercase()
        return if (lower.contains("lookup") || lower.contains("resolver") ||
            lower.contains("no such host") || lower.contains("connection refused")
        ) {
            "$base — DNS resolution to Cloudflare is failing on this device's " +
                "network. Check that the device has working internet access and retry."
        } else {
            base
        }
    }

    /**
     * Records WHY cloudflared died: the OS exit code plus the stderr tail. This
     * is the only signal we get when a tunnel "drops mid-session", so surface it
     * instead of letting the process vanish silently.
     */
    private fun logProcessExit(proc: Process, kind: String) {
        // stdout EOF can arrive before the kernel has reaped the child; wait briefly
        // so we read the real exit code instead of -1 ("still alive").
        try { proc.waitFor(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        val code = processExitCode(proc)
        val tail = currentStderr().takeLast(8)
        totalExits++
        val summary = "${ProcessExitDecoder.describe(code)} after ${uptimeLabel()} " +
            "appImportance=${appImportance()} fgService=${com.castla.mirror.service.MirrorForegroundService.isServiceRunning} " +
            "wasRegistered=$registered exitsThisProcess=$totalExits"
        lastExitSummary = "${com.castla.mirror.diagnostics.CrashBreadcrumbs.fmt(System.currentTimeMillis())} $summary"
        Log.w(TAG, "cloudflared exited ($kind): exitCode=$code | stderr tail: ${tail.joinToString(" || ")}")
        FileLogger.w(TAG, "cloudflared exited ($kind): $summary")
        tail.takeLast(5).forEach { FileLogger.i(TAG, "  stderr tail: $it") }
    }

    private fun uptimeLabel(): String {
        val started = processStartedAtMs
        return if (started == 0L) "?" else "${(SystemClock.elapsedRealtime() - started) / 1000}s"
    }

    /**
     * Our own process importance. CACHED/GONE at the moment cloudflared dies
     * means the app (its parent) was in the background — the freezer, the
     * phantom-process killer and the LMK all target exactly that state.
     */
    private fun appImportance(): String = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        DiagnosticNames.importance(info.importance)
    } catch (_: Throwable) { "?" }

    private fun stateLabel(): String = when {
        _isRunning.value -> "running"
        _isStarting.value -> "starting"
        else -> "stopped"
    }

    /** Compact state for the periodic health heartbeat. */
    fun healthPart(): String {
        val pids = findCloudflaredPids()
        val rss = pids.joinToString(",") { HealthMonitor.rssMb(File("/proc/$it/status")) }
        return "${stateLabel()} up=${if (process?.isAlive == true) uptimeLabel() else "-"} " +
            "cfdProcs=${pids.size}${if (pids.isNotEmpty()) " cfdRss=$rss" else ""} restarts=$restartCount exits=$totalExits"
    }

    /** Multi-line summary for the clipboard report. */
    fun diagnosticSummary(): List<String> = buildList {
        add("state=${stateLabel()} registered=$registered processAlive=${process?.isAlive == true} " +
            "uptime=${if (process?.isAlive == true) uptimeLabel() else "-"}")
        add("restartCount=$restartCount initialStartRetries=$initialStartRetries " +
            "dropsLast60s=${dropsLastMinute()} " +
            "exitsThisProcess=$totalExits")
        val pids = findCloudflaredPids()
        add("cloudflared processes: ${if (pids.isEmpty()) "none" else pids.joinToString { "pid $it rss=${HealthMonitor.rssMb(File("/proc/$it/status"))}" }}" +
            if (pids.size > 1) "  !! more than one cloudflared is running" else "")
        add("last exit: ${lastExitSummary ?: "none in this app run"}")
        _error.value?.let { add("error: $it") }
        val tail = currentStderr().takeLast(8)
        if (tail.isNotEmpty()) {
            add("cloudflared stderr tail:")
            tail.forEach { add("  $it") }
        }
    }

    /** Best-effort read of a list mutated on other threads; -1 if it changed under us. */
    private fun dropsLastMinute(): Int = try {
        TunnelRestartPolicy.dropsInWindow(ArrayList(dropTimestampsMs), SystemClock.elapsedRealtime())
    } catch (_: Throwable) { -1 }

    /**
     * PIDs of running cloudflared processes owned by this app. /proc on Android
     * only shows our own uid's processes to us, so the scan is small.
     */
    private fun findCloudflaredPids(): List<Int> = try {
        File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            ?.mapNotNull { dir ->
                val cmd = try { File(dir, "cmdline").readText() } catch (_: Throwable) { "" }
                if (cmd.contains(LIB_NAME)) dir.name.toIntOrNull() else null
            } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /**
     * Bounded retry for a tunnel that FAILED to start (never registered).
     * Covers transient DNS/network blips at startup where there is nothing to
     * "auto-restart" yet. Backs off then gives up with a clear error so the UI
     * isn't stuck on "Starting…" — the user can retry manually after network
     * recovery. [intentionalStop] suppresses retries.
     */
    private fun scheduleInitialStartRetry(reason: String) {
        if (intentionalStop) return
        if (TunnelRestartPolicy.shouldGiveUpInitial(initialStartRetries)) {
            Log.w(TAG, "Giving up after ${TunnelRestartPolicy.MAX_INITIAL_START_RETRIES} initial-start attempts: $reason")
            FileLogger.e(TAG, "Giving up after ${TunnelRestartPolicy.MAX_INITIAL_START_RETRIES} initial-start attempts: $reason")
            _error.value = failureMessage("Tunnel failed to start ($reason). Please check internet and retry.")
            return
        }
        initialStartRetries++
        val delay = TunnelRestartPolicy.initialRetryDelayMs(initialStartRetries)
        Log.w(TAG, "Tunnel did not start ($reason) — retry $initialStartRetries in ${delay}ms")
        FileLogger.w(TAG, "Tunnel did not start ($reason) — retry $initialStartRetries in ${delay}ms")
        restartJob?.cancel()
        restartJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (!intentionalStop && !_isRunning.value && !_isStarting.value) {
                Log.i(TAG, "Retrying initial tunnel start (attempt $initialStartRetries)")
                start(lastLocalPort)
            }
        }
    }

    /**
     * Reconnects the tunnel after an unexpected process exit (connector killed,
     * network blip, edge drop). Bounded tries with a growing backoff — resets
     * once a connection is re-established. [intentionalStop] suppresses retries
     * so an explicit stop or teardown stays dead.
     */
    private fun scheduleAutoRestart(reason: String) {
        if (intentionalStop) return
        restartCount++
        val now = SystemClock.elapsedRealtime()
        dropTimestampsMs.add(now)
        val drops = TunnelRestartPolicy.dropsInWindow(dropTimestampsMs, now)
        val delay = TunnelRestartPolicy.dropRetryDelayMs(restartCount, drops)
        Log.w(TAG, "Tunnel dropped ($reason) — auto-restart $restartCount in ${delay}ms (dropsInWindow=$drops)")
        FileLogger.w(TAG, "Tunnel dropped ($reason) — auto-restart $restartCount in ${delay}ms (dropsInWindow=$drops)")
        restartJob?.cancel()
        restartJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (!intentionalStop && !_isRunning.value && !_isStarting.value) {
                Log.i(TAG, "Auto-restarting cloudflared (attempt $restartCount)")
                start(lastLocalPort)
            }
        }
    }

    /**
     * Runs a permanent named tunnel using a Cloudflare Zero Trust connector
     * token. The public hostname is static (configured by the user in the
     * dashboard) and taken from [TunnelSecurityConfig.namedTunnelUrl].
     */
    private fun startNamedTunnelProcess(token: String) {
        val binary = binaryFile()
        if (!binary.exists()) {
            throw IllegalStateException("cloudflared binary not found at ${binary.absolutePath}")
        }

        val cmd = listOf(
            binary.absolutePath, "tunnel",
            "--edge-ip-version", "4",
            "--ha-connections", "1",
            "--no-autoupdate",
            "run", "--token", token
        )
        Log.i(TAG, "Starting NAMED tunnel: cloudflared tunnel run <redacted>")

        val pb = ProcessBuilder(cmd)
        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        pb.environment()["SSL_CERT_FILE"] = "/system/etc/security/cacerts/cacert.pem"

        val proc = pb.start()
        process = proc
        processStartedAtMs = SystemClock.elapsedRealtime()
        val gen = processGeneration
        FileLogger.i(TAG, "cloudflared spawned (generation=$gen) args=${cmd.drop(1).dropLast(1).joinToString(" ")} <redacted>")
        clearStderr()
        collectStderr(proc, gen)

        // Named tunnel hostname is known up front — surface it immediately.
        _tunnelUrl.value = TunnelSecurityConfig.load(context).namedTunnelUrl.ifBlank { null }

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (gen != processGeneration) return@Thread
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared: $l")
                    persistCloudflaredLine(CloudflaredLogFilter.redact(l))
                    onTunnelLine("stdout", l)
                }
                if (gen != processGeneration || intentionalStop) return@Thread
                Log.i(TAG, "cloudflared process exited")
                logProcessExit(proc, "named")
                val wasLive = _isRunning.value || registered
                _tunnelUrl.value = null
                _isRunning.value = false
                registered = false
                _isStarting.value = false
                if (wasLive) {
                    scheduleAutoRestart("named-tunnel connector dropped")
                } else {
                    scheduleInitialStartRetry("cloudflared exited before registering the named tunnel")
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Reader thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cloudflared output", e)
                FileLogger.e(TAG, "Error reading cloudflared output", e)
                if (gen != processGeneration || intentionalStop) return@Thread
                if (_isRunning.value) {
                    _tunnelUrl.value = null
                    _isRunning.value = false
                    scheduleAutoRestart("reader error: ${e.message}")
                } else {
                    scheduleInitialStartRetry("reader error before tunnel established: ${e.message}")
                }
                _isStarting.value = false
            }
        }, "cloudflared-reader").also { it.isDaemon = true; it.start() }
    }
}