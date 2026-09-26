package com.castla.mirror.input

/**
 * Turns the car page's per-finger touch reports into a MotionEvent stream that
 * looks like a real touchscreen, which is what Android 13's InputDispatcher
 * (running inside system_server) expects.
 *
 * Field crash: system_server aborted in InputDispatcher →
 * findTouchedWindowTargetsLocked → addWindowTargetLocked → InputTarget::addPointers
 * a few ms after an injected ACTION_DOWN. The old injection deviated from real
 * hardware in every way that code checks: pointer ids climbed per tap (0…31),
 * downTime was "now" on every event, a second finger arrived as an unrelated
 * ACTION_DOWN, and lost UPs were papered over with synthetic downs. This class
 * instead produces what hardware (and scrcpy) produce:
 *
 * - pointer ids are the lowest free id (a single tap is always id 0);
 * - every event carries ALL active pointers, with ACTION_POINTER_DOWN/UP + index
 *   for additional fingers;
 * - the whole gesture shares the DOWN's downTime;
 * - moves/ups for unknown pointers are dropped, never synthesized;
 * - a gesture that went stale (lost UP) or is interrupted (display change) is
 *   ended with ACTION_CANCEL before anything new starts.
 */
class TouchStream {

    class Motion(
        val action: Int,
        val downTime: Long,
        val ids: IntArray,
        val xs: FloatArray,
        val ys: FloatArray
    )

    private class Pointer(val pageId: Int, val localId: Int, var x: Float, var y: Float)

    /** Active pointers in the order they went down (index = position in MotionEvent). */
    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L
    private var lastEventAt = 0L

    val activeCount: Int get() = pointers.size

    @Synchronized
    fun onEvent(action: String, pageId: Int, x: Float, y: Float, nowMs: Long): List<Motion> {
        val out = ArrayList<Motion>(2)
        val existing = pointers.indexOfFirst { it.pageId == pageId }
        when (action) {
            "down" -> {
                if (existing >= 0) {
                    pointers[existing].x = x; pointers[existing].y = y
                    out += snapshot(ACTION_MOVE)
                } else {
                    if (pointers.isNotEmpty() && nowMs - lastEventAt > STALE_GESTURE_MS) {
                        reset()?.let { out += it }
                    }
                    if (pointers.size >= MAX_POINTERS) return out
                    val localId = (0 until MAX_POINTERS).first { id -> pointers.none { it.localId == id } }
                    if (pointers.isEmpty()) downTime = nowMs
                    pointers += Pointer(pageId, localId, x, y)
                    val index = pointers.lastIndex
                    out += snapshot(if (index == 0) ACTION_DOWN else ACTION_POINTER_DOWN or (index shl POINTER_INDEX_SHIFT))
                }
            }
            "move" -> {
                if (existing < 0) return out
                pointers[existing].x = x; pointers[existing].y = y
                out += snapshot(ACTION_MOVE)
            }
            "up", "cancel" -> {
                if (existing < 0) return out
                pointers[existing].x = x; pointers[existing].y = y
                out += when {
                    action == "cancel" -> snapshot(ACTION_CANCEL)
                    pointers.size == 1 -> snapshot(ACTION_UP)
                    else -> snapshot(ACTION_POINTER_UP or (existing shl POINTER_INDEX_SHIFT))
                }
                if (action == "cancel") pointers.clear() else pointers.removeAt(existing)
            }
        }
        lastEventAt = nowMs
        return out
    }

    /** Ends an open gesture (display changed, injector swapped, stale). Null if none. */
    @Synchronized
    fun reset(): Motion? {
        if (pointers.isEmpty()) return null
        val cancel = snapshot(ACTION_CANCEL)
        pointers.clear()
        return cancel
    }

    private fun snapshot(action: Int) = Motion(
        action = action,
        downTime = downTime,
        ids = IntArray(pointers.size) { pointers[it].localId },
        xs = FloatArray(pointers.size) { pointers[it].x },
        ys = FloatArray(pointers.size) { pointers[it].y }
    )

    companion object {
        // android.view.MotionEvent values (kept here so this class is plain-JVM testable).
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val POINTER_INDEX_SHIFT = 8
        const val MAX_POINTERS = 10
        /** A held pointer with no event for this long is treated as a lost UP. */
        const val STALE_GESTURE_MS = 5_000L
    }
}
