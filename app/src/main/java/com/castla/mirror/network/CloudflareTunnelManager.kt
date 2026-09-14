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
class CloudflareTunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        // Name the binary lands under once the CI job copies it into
        // app/src/main/jniLibs/<abi>/ ; the package manager extracts any
        // `jniLibs/<abi>/lib*.so` into nativeLibraryDir at install time.
        private const val LIB_NAME = "libcloudflared.so"
        // Regex to match the trycloudflare URL from cloudflared stdout
        private val URL_PATTERN = Regex("""https://[a-zA-Z0-9\-]+\.trycloudflare\.com""")
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
            Log.w(TAG, "Tunnel already running or starting")
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
        // cloudflared is a static Go binary — on Android its default CA lookup
        // paths (/etc/ssl/certs/...) don't exist, so TLS verification against
        // api.trycloudflare.com fails with "certificate signed by unknown
        // authority". Point it at Android's system CA store (world-readable
        // hashed dir present since Android 7, minSdk 26).
        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        pb.environment()["SSL_CERT_FILE"] = "/system/etc/security/cacerts/cacert.pem"

        val proc = pb.start()
        process = proc

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
                    _error.value = "cloudflared exited without establishing a tunnel"
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
                    _error.value = "cloudflared exited before registering the named tunnel"
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