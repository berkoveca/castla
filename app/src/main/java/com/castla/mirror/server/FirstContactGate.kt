package com.castla.mirror.server

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admits exactly one non-loopback source per instance even under concurrent
 * calls — the initial page load fires parallel asset requests that race into
 * this gate. Loopback (the SERVER_READY self-probe) never consumes the slot.
 */
class FirstContactGate {

    private val acquired = AtomicBoolean(false)

    fun tryAcquire(src: String?): Boolean {
        if (src == null || src == "127.0.0.1" || src == "::1" || src == "0:0:0:0:0:0:0:1") return false
        return acquired.compareAndSet(false, true)
    }
}
