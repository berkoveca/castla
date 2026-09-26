package com.castla.mirror.server

import java.util.concurrent.Executor

/**
 * Runs car requests (app launch, HOME) one at a time off the WebSocket thread.
 *
 * A launch can take seconds (`am force-stop` + `am start -W` on a cold app).
 * Run inline, it blocked the control socket — touches stalled — and taps made
 * meanwhile queued up and ran later, so the car showed an app tapped earlier.
 * Here, requests still waiting for the same [submit] key collapse to the newest
 * one; different keys (primary / secondary pane) keep their order.
 */
class LatestWinsRunner<T>(
    private val executor: Executor,
    private val run: (T) -> Unit,
    private val superseded: (T) -> Unit = {},
    private val failed: (T, Throwable) -> Unit = { _, _ -> }
) {
    private val pending = LinkedHashMap<String, T>()
    private var running = false

    fun submit(key: String, item: T) {
        val dropped: T?
        val start: Boolean
        synchronized(this) {
            dropped = pending.remove(key)
            pending[key] = item
            start = !running
            if (start) running = true
        }
        dropped?.let(superseded)
        if (start) executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val item = synchronized(this) {
                val it = pending.entries.iterator()
                if (!it.hasNext()) {
                    running = false
                    return
                }
                val next = it.next().value
                it.remove()
                next
            }
            try {
                run(item)
            } catch (t: Throwable) {
                failed(item, t)
            }
        }
    }
}
