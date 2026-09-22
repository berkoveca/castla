package com.castla.mirror.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
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

/**
 * Manages Cloudflare tunnels exposing the local MirrorServer (port 9090).
 *
 * Supports two modes:
 *  - Quick tunnel: `cloudflared tunnel --url ...` → temporary
 *    `*.trycloudflare.com` URL that changes each run.
 *  - Named tunnel: `cloudflared tunnel run <token>` → permanent hostname
 *    configured by the user in the Cloudflare Zero Trust dashboard.
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
        // Name the binary lands under once the CI job copies it into
        // app/src/main/jniLibs/<abi>/ ; the package manager extracts any
        // `jniLibs/<abi>/lib*.so` into nativeLibraryDir at install time.
        private const val LIB_NAME = "libcloudflared.so"
        // Regex to match the trycloudflare URL from cloudflared output. The
        // bionic (Termux) build prints it on stderr; the static build on stdout,
        // so both streams are scanned with this.
        private val URL_PATTERN = Regex("""https://[a-zA-Z0-9\-]+\.trycloudflare\.com""")
        // Timing / retry numbers live in [TunnelRestartPolicy] so they can be
        // unit-tested without spawning a process.

        /**
         * App-wide singleton so a running cloudflared process + `*.trycloudflare.com`
         * URL survives mirroring-session boundaries. Without this, every session start
         * spawns a fresh tunnel process and a new URL (the "constantly starting
         * tunnel" symptom).
         */
        @Volatile private var instance: CloudflareTunnelManager? = null

        fun getInstance(context: Context): CloudflareTunnelManager =
            instance ?: synchronized(this) {
                instance ?: CloudflareTunnelManager(context.applicationContext).also { instance = it }
            }
    }

    private val scopeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in tunnel coroutine", throwable)
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
     * Start cloudflared pointing at the given local port.
     *
     * Two modes:
     *  - Quick tunnel (default): `cloudflared tunnel --url ...` → temporary
     *    `*.trycloudflare.com` URL parsed from stdout.
     *  - Named tunnel: when a Zero Trust connector token is configured, runs
     *    `cloudflared tunnel run <token>` for a permanent configured hostname.
     */
    fun start(localPort: Int = 9090) {
        val config = TunnelSecurityConfig.load(context)
        val useNamed = TunnelSecurityConfig.shouldUseNamedTunnel(config)
        Log.i(TAG, "Tunnel mode=${if (useNamed) "NAMED(stable)" else "QUICK(temporal)"} " +
            "tokenPresent=${config.namedTunnelToken.isNotBlank()} " +
            "urlConfigured=${config.namedTunnelUrl.isNotBlank()} enabled=${config.namedTunnelEnabled}")
        lifecycleLock.lock()
        try {
            if (process?.isAlive == true && (_isRunning.value || _isStarting.value)) {
                Log.i(TAG, "Tunnel already running/starting — reusing existing tunnel")
                return
            }
            if (process != null) {
                Log.w(TAG, "Destroying leftover cloudflared before a new start")
                destroyProcessLocked()
            }

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
                if (TunnelSecurityConfig.shouldUseNamedTunnel(config)) {
                    startNamedTunnelProcess(config.namedTunnelToken)
                } else {
                    startQuickTunnelProcess(localPort)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
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

    fun stop() {
        lifecycleLock.lock()
        try {
            Log.i(TAG, "Stopping tunnel")
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
                    synchronized(stderrTail) {
                        stderrTail.add(l)
                        while (stderrTail.size > 40) stderrTail.removeFirst()
                    }
                    onTunnelLine("stderr", l)
                }
            } catch (e: Exception) {
                Log.d(TAG, "cloudflared stderr collector ended")
            }
        }, "cloudflared-stderr").also { it.isDaemon = true; it.start() }
    }

    private fun currentStderr(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    private fun clearStderr() = synchronized(stderrTail) { stderrTail.clear() }

    /**
     * Shared line handler for both stdout and stderr: flips running/starting when
     * a quick-tunnel URL or a registered named-tunnel connection appears on
     * either stream (stream choice differs between the static and bionic builds).
     */
    private fun onTunnelLine(stream: String, l: String) {
        val urlMatch = URL_PATTERN.find(l)
        if (urlMatch != null) {
            val url = urlMatch.value
            Log.i(TAG, "Tunnel URL: $url ($stream)")
            restartCount = 0
            initialStartRetries = 0
            restartJob?.cancel()
            _tunnelUrl.value = url
            _isRunning.value = true
            _isStarting.value = false
            return
        }
        if (!registered &&
            (l.contains("Registered tunnel connection") || l.contains("Registered tunnel network"))
        ) {
            registered = true
            restartCount = 0
            initialStartRetries = 0
            restartJob?.cancel()
            Log.i(TAG, "Named tunnel connection registered ($stream)")
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
        val code = processExitCode(proc)
        val tail = currentStderr().takeLast(8)
        Log.w(TAG, "cloudflared exited ($kind): exitCode=$code | stderr tail: ${tail.joinToString(" || ")}")
    }

    private fun startQuickTunnelProcess(localPort: Int) {
        val binary = binaryFile()
        if (!binary.exists()) {
            throw IllegalStateException("cloudflared binary not found at ${binary.absolutePath}")
        }

        val cmd = listOf(
            binary.absolutePath,
            "tunnel",
            "--url", "http://127.0.0.1:$localPort",
            "--protocol", "http2",
            "--edge-ip-version", "4",
            "--ha-connections", "1",
            "--no-autoupdate"
        )
        Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        // The bionic build uses Android's CA store only if told where it is, so
        // point it at /system/etc/security/cacerts (world-readable hashed dir
        // present since Android 7, minSdk 26).
        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        pb.environment()["SSL_CERT_FILE"] = "/system/etc/security/cacerts/cacert.pem"

        val proc = pb.start()
        process = proc
        val gen = processGeneration
        clearStderr()
        collectStderr(proc, gen)

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (gen != processGeneration) return@Thread
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared: $l")
                    onTunnelLine("stdout", l)
                }
                if (gen != processGeneration || intentionalStop) return@Thread
                Log.i(TAG, "cloudflared process exited")
                logProcessExit(proc, "quick")
                if (_isRunning.value) {
                    _tunnelUrl.value = null
                    _isRunning.value = false
                    _error.value = null
                    scheduleAutoRestart("SSL/network dropped the tunnel")
                } else {
                    scheduleInitialStartRetry("cloudflared exited without establishing a tunnel")
                }
                _isStarting.value = false
            } catch (e: InterruptedException) {
                Log.d(TAG, "Reader thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cloudflared output", e)
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
            _error.value = failureMessage("Tunnel failed to start ($reason). Please check internet and retry.")
            return
        }
        initialStartRetries++
        val delay = TunnelRestartPolicy.initialRetryDelayMs(initialStartRetries)
        Log.w(TAG, "Tunnel did not start ($reason) — retry $initialStartRetries in ${delay}ms")
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
        val gen = processGeneration
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