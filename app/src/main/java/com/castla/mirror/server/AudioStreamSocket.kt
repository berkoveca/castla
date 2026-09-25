package com.castla.mirror.server

import android.util.Log
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.DiagnosticSanitizer
import com.castla.mirror.diagnostics.MirrorDiagnostics
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException

class AudioStreamSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val server: MirrorServer
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "AudioStreamSocket"
    }

    override fun onOpen() {
        server.registerAudioSocket(this)
        val ip = runCatching { handshakeRequest.remoteIpAddress }.getOrNull()
        MirrorDiagnostics.log(DiagnosticEvent.SOCKET_OPENED,
            "[audio] remote=${DiagnosticSanitizer.maskIp(ip)}")
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        server.unregisterAudioSocket(this)
        MirrorDiagnostics.log(DiagnosticEvent.SOCKET_CLOSED,
            "[audio] code=$code reason=${reason ?: "<none>"} remoteInitiated=$initiatedByRemote")
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = message.textPayload ?: return
        if (text == "requestPcm") {
            Log.i(TAG, "Client requested PCM audio fallback")
            server.onAudioCodecRequest("pcm")
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onException(exception: IOException?) {
        Log.w(TAG, "WebSocket exception", exception)
        server.unregisterAudioSocket(this)
        MirrorDiagnostics.log(DiagnosticEvent.SOCKET_EXCEPTION,
            "[audio] ${exception?.javaClass?.simpleName ?: "unknown"}: ${exception?.message ?: ""}")
    }

    fun sendBinary(data: ByteArray) {
        try {
            send(data)
        } catch (e: IOException) {
            Log.w(TAG, "Send failed", e)
            throw e
        }
    }
}
