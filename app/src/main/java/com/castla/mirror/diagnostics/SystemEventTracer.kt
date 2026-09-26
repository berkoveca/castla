package com.castla.mirror.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display

/**
 * Session-scoped tracing of system events that precede soft reboots:
 *
 * - Display added / removed / changed (size, state) as system_server sees them —
 *   e.g. our virtual display disappearing without Castla releasing it.
 * - Main-thread stalls: a blocked main thread is the app-side sign of an
 *   overloaded phone (or a binder call into a stuck system_server).
 */
class SystemEventTracer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchThread: HandlerThread? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private val lastState = HashMap<Int, String>()
    @Volatile private var lastMainTickAt = 0L
    @Volatile private var stallReportedFor = 0L

    fun start() {
        stop()
        startDisplayTrace()
        startMainThreadWatch()
    }

    fun stop() {
        displayListener?.let { l ->
            try { (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(l) } catch (_: Throwable) {}
        }
        displayListener = null
        watchThread?.quitSafely()
        watchThread = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun describe(dm: DisplayManager, id: Int): String {
        val d = try { dm.getDisplay(id) } catch (_: Throwable) { null } ?: return "gone"
        val mode = try { d.mode } catch (_: Throwable) { null }
        val state = when (d.state) {
            Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"
            Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"; Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
            else -> "state${d.state}"
        }
        return "'${d.name}' ${mode?.physicalWidth ?: "?"}x${mode?.physicalHeight ?: "?"} $state flags=0x${Integer.toHexString(d.flags)}"
    }

    private fun startDisplayTrace() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        try {
            dm.displays.forEach { lastState[it.displayId] = describe(dm, it.displayId) }
            FileLogger.i(TAG, "displays at start: " + lastState.entries.joinToString("; ") { "${it.key}=${it.value}" })
        } catch (_: Throwable) {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val s = describe(dm, displayId)
                lastState[displayId] = s
                FileLogger.i(TAG, "display added $displayId $s")
            }
            override fun onDisplayRemoved(displayId: Int) {
                lastState.remove(displayId)
                FileLogger.i(TAG, "display removed $displayId", durable = true)
            }
            override fun onDisplayChanged(displayId: Int) {
                val s = describe(dm, displayId)
                if (lastState[displayId] == s) return // refresh-rate / brightness noise
                lastState[displayId] = s
                FileLogger.i(TAG, "display changed $displayId $s")
            }
        }
        try {
            dm.registerDisplayListener(listener, mainHandler)
            displayListener = listener
        } catch (t: Throwable) {
            FileLogger.w(TAG, "display listener unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun startMainThreadWatch() {
        val thread = HandlerThread("diag-mainwatch").apply { start() }
        watchThread = thread
        val watch = Handler(thread.looper)
        lastMainTickAt = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                lastMainTickAt = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
        mainHandler.post(tick)
        watch.post(object : Runnable {
            override fun run() {
                val lag = SystemClock.elapsedRealtime() - lastMainTickAt
                if (lag > STALL_MS && lastMainTickAt != stallReportedFor) {
                    stallReportedFor = lastMainTickAt
                    FileLogger.w(TAG, "main thread blocked ${lag}ms (overload or a stuck system call)")
                }
                watch.postDelayed(this, TICK_MS)
            }
        })
    }

    companion object {
        private const val TAG = "SysEvent"
        private const val TICK_MS = 1_000L
        private const val STALL_MS = 2_500L
    }
}
