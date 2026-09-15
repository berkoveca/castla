package com.castla.mirror.network

import android.content.Context
import android.util.Log
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
        // Regex to match the trycloudflare URL from cloudflared stdout
        private val URL_PATTERN = Regex("""https://[a-zA-Z0-9\-]+\.trycloudflare\.com""")

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

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var process: Process? = null
    private var readerThread: Thread? = null
    private var downloadJob: Job? = null

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
        if (_isRunning.value || _isStarting.value) {
            Log.i(TAG, "Tunnel already running/starting — reusing existing tunnel")
            return
        }

        val config = TunnelSecurityConfig.load(context)
        _isStarting.value = true
        _error.value = null

        downloadJob = scope.launch {
            try {
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
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping tunnel")
        downloadJob?.cancel()
        readerThread?.interrupt()
        readerThread = null

        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy process", e)
        }
        process = null
        _tunnelUrl.value = null
        _isRunning.value = false
        _isStarting.value = false
        _error.value = null
    }

    /**
     * cloudflared writes its real errors to stderr. We ship a bionic-linked build
     * (from Termux's package) so DNS resolves through Android's netd like any app,
     * but capture the stderr tail anyway so failures are explained instead of an
     * opaque "exited without establishing a tunnel".
     */
    private val stderrTail = java.util.LinkedList<String>()

    private fun collectStderr(proc: Process) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared[err]: $l")
                    synchronized(stderrTail) {
                        stderrTail.add(l)
                        while (stderrTail.size > 40) stderrTail.removeFirst()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "cloudflared stderr collector ended")
            }
        }, "cloudflared-stderr").also { it.isDaemon = true; it.start() }
    }

    private fun currentStderr(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    private fun clearStderr() = synchronized(stderrTail) { stderrTail.clear() }

    private fun failureMessage(fallback: String): String {
        val lastStderr = currentStderr()
        val exitCode = try { process?.waitFor() ?: -1 } catch (e: Exception) { -1 }
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
        clearStderr()
        collectStderr(proc)

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared: $l")

                    val match = URL_PATTERN.find(l)
                    if (match != null) {
                        val url = match.value
                        Log.i(TAG, "Tunnel URL: $url")
                        _tunnelUrl.value = url
                        _isRunning.value = true
                        _isStarting.value = false
                    }
                }
                // Process exited
                Log.i(TAG, "cloudflared process exited")
                if (_isRunning.value) {
                    _tunnelUrl.value = null
                    _isRunning.value = false
                } else {
                    _error.value = failureMessage("cloudflared exited without establishing a tunnel")
                }
                _isStarting.value = false
            } catch (e: InterruptedException) {
                Log.d(TAG, "Reader thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cloudflared output", e)
                _error.value = e.message
                _isStarting.value = false
            }
        }, "cloudflared-reader").also { it.isDaemon = true; it.start() }
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

        val cmd = listOf(binary.absolutePath, "tunnel", "run", token)
        Log.i(TAG, "Starting NAMED tunnel: cloudflared tunnel run <redacted>")

        val pb = ProcessBuilder(cmd)
        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        pb.environment()["SSL_CERT_FILE"] = "/system/etc/security/cacerts/cacert.pem"

        val proc = pb.start()
        process = proc
        clearStderr()
        collectStderr(proc)

        // Named tunnel hostname is known up front — surface it immediately.
        _tunnelUrl.value = TunnelSecurityConfig.load(context).namedTunnelUrl.ifBlank { null }

        var registered = false
        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    Log.d(TAG, "cloudflared: $l")

                    if (!registered &&
                        (l.contains("Registered tunnel connection") || l.contains("Registered tunnel network"))
                    ) {
                        registered = true
                        Log.i(TAG, "Named tunnel connection registered")
                        _isRunning.value = true
                        _isStarting.value = false
                    }
                }
                // Process exited
                Log.i(TAG, "cloudflared process exited")
                if (_isRunning.value || registered) {
                    _tunnelUrl.value = null
                    _isRunning.value = false
                }
                _isStarting.value = false
                if (!registered) {
                    _error.value = failureMessage("cloudflared exited before registering the named tunnel")
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Reader thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cloudflared output", e)
                _error.value = e.message
                _isStarting.value = false
            }
        }, "cloudflared-reader").also { it.isDaemon = true; it.start() }
    }
}