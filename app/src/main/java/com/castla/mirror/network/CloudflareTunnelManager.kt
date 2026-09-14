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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Cloudflare tunnels exposing the local MirrorServer (port 9090).
 *
 * Supports two modes:
 *  - Quick tunnel: `cloudflared tunnel --url ...` → temporary
 *    `*.trycloudflare.com` URL that changes each run.
 *  - Named tunnel: `cloudflared tunnel run <token>` → permanent hostname
 *    configured by the user in the Cloudflare Zero Trust dashboard.
 *
 * The binary is downloaded from Cloudflare's GitHub releases on first use and
 * cached in the app's files dir.
 */
class CloudflareTunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        private const val BINARY_NAME = "cloudflared"
        private const val RELEASES_API = "https://api.github.com/repos/cloudflare/cloudflared/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
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

    private fun binaryFile(): File = File(context.filesDir, BINARY_NAME)

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
     * Downloads the binary first if necessary.
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
                if (!isBinaryDownloaded()) {
                    Log.i(TAG, "Downloading cloudflared binary...")
                    downloadBinary()
                }
                if (TunnelSecurityConfig.hasNamedTunnel(config)) {
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
            .directory(context.filesDir)
            .redirectErrorStream(true)
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
            .directory(context.filesDir)
            .redirectErrorStream(true)
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

    private fun downloadBinary() {
        // Get latest release info from GitHub API
        val releaseUrl = URL(RELEASES_API)
        val conn = releaseUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "Castla/${context.packageName}")

        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("GitHub API returned ${conn.responseCode}")
            }

            val json = conn.inputStream.bufferedReader().readText()
            // Parse download URL for android-arm64
            val downloadUrl = parseDownloadUrl(json)
                ?: throw RuntimeException("Could not find cloudflared binary for arm64 in latest release")

            Log.i(TAG, "Downloading from: $downloadUrl")
            downloadFile(downloadUrl, binaryFile())
            binaryFile().setExecutable(true)
            Log.i(TAG, "Binary downloaded and made executable")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDownloadUrl(json: String): String? {
        // Simple JSON parsing without external library
        // Look for "browser_download_url" entries containing "cloudflared-linux-arm64"
        val pattern = """"browser_download_url"\s*:\s*"([^"]*cloudflared-linux-arm64[^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS

        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("Download returned ${conn.responseCode}")
            }

            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct % 10 == 0) Log.d(TAG, "Download: $pct%")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
