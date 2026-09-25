package com.castla.mirror.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import org.json.JSONObject
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.DiagnosticSanitizer
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.network.ReachableIp
import com.castla.mirror.network.TunnelSecurityConfig
import com.castla.mirror.ott.OttCatalog
import com.castla.mirror.utils.AppCategoryClassifier

data class TouchEvent(val action: String, val x: Float, val y: Float, val pointerId: Int, val pane: String = "primary")

class MirrorServer(private val context: Context) : NanoWSD(DEFAULT_PORT) {

    companion object {
        private const val TAG = "MirrorServer"
        const val DEFAULT_PORT = 9090
        private const val COOKIE_AUTH = "castla_auth"
        private val REPLAYED_TYPES = listOf("streamingMode", "streamCodec")
    }

    // Copy-on-write: sockets are added/removed on NanoHTTPD request threads while the
    // encoder/audio threads iterate these sets to broadcast. Plain HashSets threw
    // ConcurrentModificationException (lost frames) under rapid reconnects.
    private val primaryVideoSockets = java.util.concurrent.CopyOnWriteArraySet<VideoStreamSocket>()
    private val secondaryVideoSockets = java.util.concurrent.CopyOnWriteArraySet<VideoStreamSocket>()
    private val controlSockets = java.util.concurrent.CopyOnWriteArraySet<ControlSocket>()
    private val audioSockets = java.util.concurrent.CopyOnWriteArraySet<AudioStreamSocket>()

    /** Latest control message per replayable type, sent to every new control socket. */
    private val replayedControlMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val keepAliveExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "WS-KeepAlive").apply { isDaemon = true }
    }.also { ex ->
        val period = com.castla.mirror.policy.KeepAlivePolicy.SERVER_INTERVAL_MS
        ex.scheduleWithFixedDelay({ sendKeepAlives() }, period, period, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Keeps every socket above Cloudflare's 100 s idle cutoff and surfaces
     * half-open connections: a failed ping throws, and the socket is dropped.
     * Control gets an app-level message because browsers hide ping frames from
     * JS, and the page uses it to detect a dead control socket.
     */
    private fun sendKeepAlives() {
        try {
            val ka = "{\"type\":\"ka\",\"t\":${System.currentTimeMillis()}}"
            for (s in controlSockets) {
                try { s.send(ka) } catch (e: Exception) { unregisterControlSocket(s) }
            }
            for (s in primaryVideoSockets) runCatching { s.ping(ByteArray(0)) }
            for (s in secondaryVideoSockets) runCatching { s.ping(ByteArray(0)) }
            for (s in audioSockets) runCatching { s.ping(ByteArray(0)) }
        } catch (t: Throwable) {
            Log.w(TAG, "keepalive pass failed", t)
        }
    }

    override fun stop() {
        keepAliveExecutor.shutdownNow()
        super.stop()
    }

    private var onTouchListener: ((TouchEvent) -> Unit)? = null
    private var onCodecModeListener: ((String) -> Unit)? = null
    private var onViewportChangeListener: ((String, Int, Int, String, Float) -> Unit)? = null
    private var onTextInputListener: ((String) -> Unit)? = null
    private var onKeyEventListener: ((Int) -> Unit)? = null
    private var onCompositionUpdateListener: ((Int, String) -> Unit)? = null
    private var onAudioCodecListener: ((String) -> Unit)? = null
    private var onPrimaryKeyframeRequest: (() -> Unit)? = null
    private var onSecondaryKeyframeRequest: (() -> Unit)? = null
    private var networkCongestionListener: (() -> Unit)? = null
    
    // Web Launcher specific listeners
    private var onGoHomeListener: (() -> Unit)? = null
    private var onAppLaunchListener: ((String, String?, Boolean, String) -> Unit)? = null
    private var onCloseSplitListener: (() -> Unit)? = null
    private var onDisplayDensityListener: ((Float) -> Unit)? = null
    private var onQualityReportListener: ((Int, Double, Int) -> Unit)? = null
    private var onBubbleClosedListener: (() -> Unit)? = null

    // Track active connection status
    private var isBrowserConnected = false
    // One HTTP_FIRST_CONTACT per server instance (= per session; recreated each pipeline start)
    private val httpFirstContact = FirstContactGate()
    private var onBrowserConnectionListener: ((Boolean) -> Unit)? = null
    private var onAudioSocketConnectedListener: (() -> Unit)? = null

    // Cached thermal status JSON — sent immediately to new control sockets
    // to prevent race where browser connects before thermal broadcast arrives.
    @Volatile private var cachedThermalJson: String? = null

    private var cachedSpsPps: ByteArray? = null

    fun setTouchListener(listener: (TouchEvent) -> Unit) {
        onTouchListener = listener
    }

    fun setCodecModeListener(listener: (String) -> Unit) {
        onCodecModeListener = listener
    }

    fun setViewportChangeListener(listener: (String, Int, Int, String, Float) -> Unit) {
        onViewportChangeListener = listener
    }

    fun setTextInputListener(listener: (String) -> Unit) {
        onTextInputListener = listener
    }

    fun setKeyEventListener(listener: (Int) -> Unit) {
        onKeyEventListener = listener
    }

    fun setCompositionUpdateListener(listener: (Int, String) -> Unit) {
        onCompositionUpdateListener = listener
    }

    fun setAudioCodecListener(listener: (String) -> Unit) {
        onAudioCodecListener = listener
    }

    fun setKeyframeRequester(channel: String = "primary", requester: () -> Unit) {
        if (channel == "secondary") onSecondaryKeyframeRequest = requester else onPrimaryKeyframeRequest = requester
    }
    
    fun setNetworkCongestionListener(listener: () -> Unit) {
        networkCongestionListener = listener
    }

    fun setBrowserConnectionListener(listener: ((Boolean) -> Unit)?) {
        onBrowserConnectionListener = listener
        // Fire immediately if already connected
        if (isBrowserConnected) listener?.invoke(true)
    }

    fun setAudioSocketConnectedListener(listener: (() -> Unit)?) {
        onAudioSocketConnectedListener = listener
    }

    fun setGoHomeListener(listener: () -> Unit) {
        onGoHomeListener = listener
    }
    
    fun setAppLaunchListener(listener: (String, String?, Boolean, String) -> Unit) {
        onAppLaunchListener = listener
    }

    fun setCloseSplitListener(listener: () -> Unit) {
        onCloseSplitListener = listener
    }

    fun setDisplayDensityListener(listener: (Float) -> Unit) {
        onDisplayDensityListener = listener
    }

    fun setQualityReportListener(listener: (Int, Double, Int) -> Unit) {
        onQualityReportListener = listener
    }

    fun setBubbleClosedListener(listener: () -> Unit) {
        onBubbleClosedListener = listener
    }

    fun isBrowserConnected(): Boolean = isBrowserConnected


    fun onCloseSplitRequest() {
        onCloseSplitListener?.invoke()
    }

    fun onDisplayDensityChange(scale: Float) {
        onDisplayDensityListener?.invoke(scale)
    }

    private fun updateConnectionState() {
        val connected = primaryVideoSockets.isNotEmpty() || secondaryVideoSockets.isNotEmpty() || controlSockets.isNotEmpty()
        if (connected != isBrowserConnected) {
            isBrowserConnected = connected
            if (!connected) {
                MirrorDiagnostics.log(DiagnosticEvent.SOCKET_DISCONNECTED,
                    "all browser sockets closed")
            } else {
                // Every 0→1 transition, not just the first: the classifier uses
                // this to treat earlier socket failures as recovered.
                MirrorDiagnostics.log(DiagnosticEvent.WS_CONNECTED, "browser socket registered")
            }
            onBrowserConnectionListener?.invoke(connected)
        }
    }

    fun registerVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.add(socket)
        Log.i(TAG, "$channel video client connected (total: ${sockets.size})")

        val cached = if (channel == "secondary") cachedSecondarySpsPps else cachedPrimarySpsPps
        cached?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Sent cached SPS/PPS to new $channel video client")
        }

        updateConnectionState()
        onKeyframeRequest(channel)
    }

    fun unregisterVideoSocket(channel: String, socket: VideoStreamSocket) {
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        sockets.remove(socket)
        Log.i(TAG, "$channel video client disconnected (total: ${sockets.size})")
        updateConnectionState()
    }

    fun registerControlSocket(socket: ControlSocket) {
        controlSockets.add(socket)
        Log.i(TAG, "Control client connected (total: ${controlSockets.size})")

        // Replay cached thermal status to new client immediately
        cachedThermalJson?.let { json ->
            try { socket.send(json) }
            catch (e: Exception) { Log.w(TAG, "Failed to send cached thermal status", e) }
        }
        // ...and the streaming mode / codec hint: they are broadcast once per
        // browser connection, so a control socket that reconnected inside the grace
        // window (page reload) would otherwise never learn them.
        for (json in replayedControlMessages.values) {
            try { socket.send(json) } catch (e: Exception) { Log.w(TAG, "Failed to replay control message", e) }
        }

        updateConnectionState()
    }

    fun unregisterControlSocket(socket: ControlSocket) {
        controlSockets.remove(socket)
        Log.i(TAG, "Control client disconnected (total: ${controlSockets.size})")
        updateConnectionState()
    }

    fun registerAudioSocket(socket: AudioStreamSocket) {
        audioSockets.add(socket)
        Log.i(TAG, "Audio client connected (total: ${audioSockets.size})")
        onAudioSocketConnectedListener?.invoke()
        cachedAudioConfig?.let {
            socket.sendBinary(it)
            Log.i(TAG, "Replayed audio config to new client (${it.size} bytes)")
        }
    }

    fun unregisterAudioSocket(socket: AudioStreamSocket) {
        audioSockets.remove(socket)
        Log.i(TAG, "Audio client disconnected (total: ${audioSockets.size})")
    }

    private var primaryFrameSeqNum: Int = 0
    private var secondaryFrameSeqNum: Int = 0

    private fun fillVideoHeader(data: ByteArray, flags: Byte, seq: Int) {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        data[0] = flags
        data[1] = (seq and 0xFF).toByte()
        data[2] = ((seq shr 8) and 0xFF).toByte()
        data[3] = (tsMs and 0xFF).toByte()
        data[4] = ((tsMs shr 8) and 0xFF).toByte()
        data[5] = ((tsMs shr 16) and 0xFF).toByte()
        data[6] = ((tsMs shr 24) and 0xFF).toByte()
        data[7] = 0.toByte() // reserved
    }

    private fun buildVideoHeader(flags: Byte, seq: Int): ByteArray {
        val tsMs = android.os.SystemClock.elapsedRealtime().toInt()
        val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(flags)
        buf.putShort(seq.toShort())  // seqLo, seqHi (2 bytes LE)
        buf.putInt(tsMs)             // tsMs_0~3 (4 bytes LE)
        buf.put(0.toByte())          // reserved
        return buf.array()
    }

    private var cachedPrimarySpsPps: ByteArray? = null
    private var cachedSecondarySpsPps: ByteArray? = null

    fun broadcastSpsPps(data: ByteArray, channel: String = "primary") {
        val buffer = ByteArray(8 + data.size)
        fillVideoHeader(buffer, 0x02, 0)
        System.arraycopy(data, 0, buffer, 8, data.size)
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        if (channel == "secondary") cachedSecondarySpsPps = buffer else cachedPrimarySpsPps = buffer

        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    /**
     * Send an fMP4 init segment to the video sockets. Uses the same 8-byte header
     * as [broadcastSpsPps] with flag 0x02 so the client's MSE decoder can tell an
     * init segment apart from media fragments (flag 0x01/0x00).
     */
    fun broadcastFmp4Init(data: ByteArray, channel: String = "primary") {
        val buffer = ByteArray(8 + data.size)
        fillVideoHeader(buffer, 0x02, 0)
        System.arraycopy(data, 0, buffer, 8, data.size)
        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(buffer)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    fun broadcastFrame(data: ByteArray, isKeyFrame: Boolean, channel: String = "primary") {
        val seq = if (channel == "secondary") ++secondaryFrameSeqNum else ++primaryFrameSeqNum
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00
        
        // Check if this is a pre-allocated array from VideoEncoder (size > 8)
        val frame = if (data.size > 8 && data[8] == 0.toByte() && data[9] == 0.toByte() && data[10] == 0.toByte() && data[11] == 1.toByte()) {
            // New VideoEncoder: The 8-byte padding is already at the start, just fill it in
            fillVideoHeader(data, flags, seq)
            data
        } else if (data.size > 8 && (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 0.toByte())) {
            // New VideoEncoder: The 8 bytes are empty. Fill them.
            fillVideoHeader(data, flags, seq)
            data
        } else {
            // Fallback for MJPEG Encoder or old pipelines that don't pre-allocate 8 bytes
            val header = buildVideoHeader(flags, seq)
            header + data
        }

        val sockets = if (channel == "secondary") secondaryVideoSockets else primaryVideoSockets
        val deadSockets = mutableListOf<VideoStreamSocket>()
        for (socket in sockets) {
            try {
                socket.sendBinary(frame)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterVideoSocket(channel, it) }
    }

    private var cachedAudioConfig: ByteArray? = null

    fun broadcastAudio(data: ByteArray) {
        if (data.isNotEmpty() && data[0] == 0x00.toByte()) {
            cachedAudioConfig = data
        }

        val deadSockets = mutableListOf<AudioStreamSocket>()
        for (socket in audioSockets) {
            try {
                socket.sendBinary(data)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterAudioSocket(it) }
    }

    fun controlSocketCount(): Int = controlSockets.size

    fun broadcastControlMessage(json: String) {
        // Cache thermal status so new control sockets receive it immediately
        if (json.contains("\"thermalStatus\"")) {
            cachedThermalJson = json
        }
        for (type in REPLAYED_TYPES) {
            if (json.contains("\"type\":\"$type\"")) replayedControlMessages[type] = json
        }
        val deadSockets = mutableListOf<ControlSocket>()
        for (socket in controlSockets) {
            try {
                socket.send(json)
            } catch (e: Exception) {
                deadSockets.add(socket)
            }
        }
        deadSockets.forEach { unregisterControlSocket(it) }
    }
    
    // Callbacks from ControlSocket
    fun onTouchEvent(event: TouchEvent) {
        onTouchListener?.invoke(event)
    }
    
    fun onKeyframeRequest(channel: String = "primary") {
        if (channel == "secondary") onSecondaryKeyframeRequest?.invoke() else onPrimaryKeyframeRequest?.invoke()
    }
    
    fun onNetworkCongestion() {
        networkCongestionListener?.invoke()
    }
    
    fun onCodecModeRequest(mode: String) {
        onCodecModeListener?.invoke(mode)
    }
    
    fun onViewportChange(pane: String, width: Int, height: Int, layoutMode: String = "", dpr: Float = 1f) {
        onViewportChangeListener?.invoke(pane, width, height, layoutMode, dpr)
    }
    
    fun onTextInput(text: String) {
        onTextInputListener?.invoke(text)
    }
    
    fun onKeyEvent(keyCode: Int) {
        onKeyEventListener?.invoke(keyCode)
    }
    
    fun onCompositionUpdate(backspaces: Int, text: String) {
        onCompositionUpdateListener?.invoke(backspaces, text)
    }
    
    fun onGoHomeRequest() {
        onGoHomeListener?.invoke()
    }
    
    fun onAudioCodecRequest(codec: String) {
        onAudioCodecListener?.invoke(codec)
    }
    
    fun onAppLaunchRequest(pkg: String, componentName: String? = null, splitMode: Boolean = false, pane: String = if (splitMode) "secondary" else "primary") {
        onAppLaunchListener?.invoke(pkg, componentName, splitMode, pane)
    }

    fun onQualityReport(droppedFrames: Int, avgDelayMs: Double, backlogDrops: Int) {
        onQualityReportListener?.invoke(droppedFrames, avgDelayMs, backlogDrops)
    }

    fun onBubbleClosed() {
        onBubbleClosedListener?.invoke()
    }


    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val config = TunnelSecurityConfig.load(context)
        if (config.authEnabled) {
            val cookieValue = parseCookie(handshake.headers["cookie"], COOKIE_AUTH)
            if (!TunnelSecurityConfig.isValidSession(context, config, cookieValue)) {
                Log.i(TAG, "Rejecting WebSocket handshake: missing/invalid auth cookie")
                throw NanoWSD.WebSocketException(
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                    "Unauthorized"
                )
            }
        }

        val uri = handshake.uri
        val channel = handshake.parameters["channel"]?.firstOrNull()
            ?: if (uri.contains("secondary")) "secondary" else "primary"

        return when {
            uri.startsWith("/ws/video") -> VideoStreamSocket(handshake, this, channel)
            uri.startsWith("/ws/control") -> ControlSocket(handshake, this)
            uri.startsWith("/ws/audio") -> AudioStreamSocket(handshake, this)
            else -> VideoStreamSocket(handshake, this, channel)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        logHttpFirstContact(session)
        var uri = session.uri
        if (uri == "/") uri = "/index.html"
        if (uri == "/index.html") {
            MirrorDiagnostics.log(
                DiagnosticEvent.PAGE_LOAD,
                "src=${DiagnosticSanitizer.maskIp(session.remoteIpAddress)} " +
                    "ua=${DiagnosticSanitizer.safeMessage(session.headers["user-agent"] ?: "<none>")}"
            )
        }

        val config = TunnelSecurityConfig.load(context)

        // NanoHTTPD only consumes the POST body for application/x-www-form-urlencoded
        // and multipart content types. Any other body (e.g. text/plain from a
        // stripped-down client, or a proxy reformatting the form) leaves its bytes
        // in the socket, which NanoHTTPD later reads as the first line of the NEXT
        // request — the "HTTP verb password=testPOST unhandled" symptom. Drain the
        // body for ordinary requests so the stream is always clean. /auth parses it
        // itself (it needs the value), so it is exempt here.
        if (uri != "/auth") {
            drainRequestBody(session)
        }

        val response = when {
            // Password login — validate and issue the session cookie
            uri == "/auth" -> handleAuthSubmit(session, config)

            // API routes bypass the auth gate (no sensitive data; used pre-login too)
            uri == "/api/apps" -> serveAppList()
            uri.startsWith("/api/icon") -> {
                val pkg = session.parameters["pkg"]?.firstOrNull()
                if (pkg != null) serveAppIcon(pkg) else serveAsset(uri)
            }

            // Auth gate — block every page until a valid session cookie is present
            config.authEnabled -> {
                val cookieValue = parseCookie(session.headers["cookie"], COOKIE_AUTH)
                if (!TunnelSecurityConfig.isValidSession(context, config, cookieValue)) {
                    if (uri != "/login.html" && uri != "/favicon.ico") serveLoginPage() else serveAsset(uri)
                } else {
                    serveAsset(uri)
                }
            }
            else -> serveAsset(uri)
        }
        // Discourage keep-alive reuse so residual bytes can never corrupt a
        // follow-up request on the same connection.
        response.addHeader("Connection", "close")
        return response
    }

    /** Reads and discards the request body so it cannot leak into the next request. */
    private fun drainRequestBody(session: IHTTPSession) {
        if (session.method == NanoHTTPD.Method.GET || session.method == NanoHTTPD.Method.HEAD) return
        try {
            session.parseBody(linkedMapOf())
        } catch (e: Exception) {
            Log.d(TAG, "No request body to drain: ${e.message}")
        }
    }

    private fun handleAuthSubmit(session: IHTTPSession, config: TunnelSecurityConfig): Response {
        val submitted = extractPassword(session)
        if (config.authPassword.isNotEmpty() && submitted == config.authPassword) {
            val token = TunnelSecurityConfig.sessionToken(context, config.authPassword)
            Log.i(TAG, "Auth success from ${session.remoteIpAddress}")
            val resp = newFixedLengthResponse(
                Response.Status.REDIRECT,
                "text/html",
                "<html><body>Redirecting...</body></html>"
            )
            resp.addHeader("Location", "/")
            // 90-day cookie; survives pipeline restarts because the session secret
            // is persistent. HttpOnly to keep the token off the page's JS.
            resp.addHeader("Set-Cookie", "$COOKIE_AUTH=$token; Path=/; HttpOnly; Max-Age=7776000")
            return resp
        }
        Log.w(TAG, "Auth failed from ${session.remoteIpAddress}")
        return serveLoginPage(showError = true)
    }

    /**
     * Reads the password from the form POST regardless of content type. NanoHTTPD
     * only decodes application/x-www-form-urlencoded into session parameters, so
     * we additionally parse the raw body ourselves (which also drains the socket).
     */
    /**
     * Reads the password from the form POST regardless of content type. NanoHTTPD
     * only decodes application/x-www-form-urlencoded into session parameters, so
     * we additionally parse the raw body ourselves (which also drains the socket).
     */
    private fun extractPassword(session: IHTTPSession): String {
        val contentType = session.headers["content-type"].orEmpty().lowercase()
        if (contentType.isNotEmpty() && !contentType.startsWith("application/x-www-form-urlencoded")) {
            Log.d(TAG, "Auth submit with non-form content type: $contentType")
        }

        // Query-string params (already decoded for GET-style /?password=... submits).
        session.parameters["password"]?.firstOrNull()?.let { return it }

        // Parse the body: NanoHTTPD fills session parameters for urlencoded forms
        // and exposes the raw body under "postData" for any other content type.
        val rawBody: String? = try {
            val parsed = linkedMapOf<String, String>()
            session.parseBody(parsed)
            parsed["postData"]
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse auth body: ${e.message}")
            null
        }

        // For urlencoded forms, decodeParms() runs inside parseBody and populates
        // the same map returned by getParameters(), so re-read after the parse.
        session.parameters["password"]?.firstOrNull()?.let { return it }

        // text/plain and other encodings — parse the raw body ourselves.
        return rawBody.orEmpty()
            .split("&")
            .mapNotNull { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size != 2) return@mapNotNull null
                if (percentDecode(kv[0]) == "password") percentDecode(kv[1]) else null
            }
            .firstOrNull()
            .orEmpty()
    }

    private fun percentDecode(input: String): String =
        try { NanoHTTPD.decodePercent(input.trim()) } catch (e: Exception) { input.trim() }

    private fun serveLoginPage(showError: Boolean = false): Response {
        var html = loginPageHtml()
        if (showError) {
            html = html.replace(
                "<div class=\"error\" id=\"error\">",
                "<div class=\"error visible\" id=\"error\">"
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun parseCookie(header: String?, name: String): String? {
        val prefix = "$name="
        header?.split(";")?.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith(prefix)) {
                val value = trimmed.substring(prefix.length)
                if (value.isNotEmpty()) return value
                return null
            }
        }
        return null
    }

    private var cachedLoginHtml: String? = null
    private fun loginPageHtml(): String {
        cachedLoginHtml?.let { return it }
        val html = try {
            context.assets.open("web/login.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read login.html", e)
            "<!DOCTYPE html><html><body style=\"background:#0a0a12;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh\">" +
                "<form method=\"post\" action=\"/auth\"><h2>Castla</h2><input type=\"password\" name=\"password\" placeholder=\"Password\" " +
                "style=\"display:block;margin:12px 0;padding:10px;border-radius:8px;border:1px solid #555;background:#111;color:#fff\">" +
                "<button type=\"submit\" style=\"padding:10px 24px;border:none;border-radius:8px;background:#64B5F6;color:#111;font-weight:bold\">Unlock</button></form></body></html>"
        }
        cachedLoginHtml = html
        return html
    }
    
    private fun logHttpFirstContact(session: IHTTPSession) {
        val src = session.remoteIpAddress
        if (!httpFirstContact.tryAcquire(src)) return
        // This request proves which of our addresses the browser can reach —
        // the one thing the priority table can only guess at (issue #51).
        ReachableIp.remember(context, session.headers["host"])
        MirrorDiagnostics.log(
            DiagnosticEvent.HTTP_FIRST_CONTACT,
            "src=${DiagnosticSanitizer.maskIp(src)} host=${DiagnosticSanitizer.sanitizeHost(session.headers["host"])}"
        )
    }

    private fun serveAppList(): Response {
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
            
            val jsonArray = org.json.JSONArray()
            resolveInfos.forEach { ri ->
                if (ri.activityInfo.packageName != context.packageName) {
                    val obj = JSONObject().apply {
                        val pkgName = ri.activityInfo.packageName
                        val className = ri.activityInfo.name
                        val componentName = android.content.ComponentName(pkgName, className)
                            .flattenToShortString()
                        val label = ri.loadLabel(pm).toString()
                        put("packageName", pkgName)
                        put("className", className)
                        put("componentName", componentName)
                        put("label", label)
                        put("category", AppCategoryClassifier.classify(pkgName, label))
                        
                        // Check if it's a DRM-restricted OTT app
                        val ottTarget = OttCatalog.resolve(pkgName)
                        put("isWeb", ottTarget != null)
                        put("webUrl", ottTarget?.webUrl ?: JSONObject.NULL)
                        put("launchMode", if (ottTarget != null) "EXTERNAL_BROWSER_URL" else "STANDARD_APP")
                    }
                    jsonArray.put(obj)
                }
            }
            
            val responseObj = JSONObject().apply {
                put("isPremium", true)
                put("fitMode", "contain")
                put("autoFit", true)
                put("layoutMode", "single")
                put("apps", jsonArray)
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve app list", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
    
    private fun serveAppIcon(packageName: String): Response {
        try {
            val pm = context.packageManager
            val icon = pm.getApplicationIcon(packageName)
            val bmp = android.graphics.Bitmap.createBitmap(
                icon.intrinsicWidth.coerceAtLeast(1), 
                icon.intrinsicHeight.coerceAtLeast(1), 
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            
            return newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon not found")
        }
    }

    /** Changes on every app start, so an updated APK always yields fresh asset URLs. */
    private val assetVersion = "${com.castla.mirror.BuildConfig.VERSION_CODE}-${System.currentTimeMillis() / 1000}"

    private fun serveAsset(uri: String): Response {
        return try {
            var path = uri.trimStart('/')
            if (path.isEmpty()) path = "index.html"
            val stream = context.assets.open("web/$path")
            val mimeType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".ico") -> "image/x-icon"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                else -> "application/octet-stream"
            }
            val resp = if (path == "index.html") {
                val html = stream.bufferedReader().use { it.readText() }
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    AssetCachePolicy.versionUrls(html, assetVersion))
            } else {
                newChunkedResponse(Response.Status.OK, mimeType, stream)
            }
            if (AssetCachePolicy.isNoStore(path)) {
                resp.addHeader("Cache-Control", AssetCachePolicy.NO_STORE)
                resp.addHeader("Pragma", "no-cache")
                resp.addHeader("Expires", "0")
            }
            resp
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
