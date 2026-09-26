package com.castla.mirror.capture

import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import com.castla.mirror.diagnostics.DiagnosticEvent
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.diagnostics.MirrorDiagnostics
import com.castla.mirror.shizuku.IPrivilegedService

/**
 * Owns the per-session virtual display lifecycle and exposes VD-scoped privileged
 * operations (input injection, app launch, surface attachment).
 *
 * Binder ownership: this class no longer binds the Shizuku user-service. The
 * `IPrivilegedService` reference is passed in via [attachPrivilegedService] by
 * the foreground service, which keeps a single long-lived [com.castla.mirror.shizuku.ShizukuSetup]
 * as the sole bind owner. Previously this class held its own ServiceConnection,
 * which produced two concurrent binds per session and a cascade of orphan VDs
 * when duplicate `onServiceConnected` callbacks were misclassified as binder
 * deaths.
 */
// Create/resize/release are @Synchronized: the first-connection setup and a
// viewport-driven rebuild used to create two displays at the same moment, and
// whichever finished last decided which display id this class tracked.
class VirtualDisplayManager {

    companion object {
        private const val TAG = "VirtualDisplayManager"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var privilegedService: IPrivilegedService? = null
    @Volatile private var displayId: Int = -1
    @Volatile private var isBound = false

    /**
     * Mirror the latest [IPrivilegedService] reference owned by `ShizukuSetup`.
     * Called on first connect and on every reconnect after a binder death.
     * Passing `null` invalidates the local VD state — the caller must follow up
     * with [createVirtualDisplay] before issuing any VD-scoped operations again.
     */
    fun attachPrivilegedService(svc: IPrivilegedService?) {
        privilegedService = svc
        isBound = svc != null
        if (svc == null) {
            virtualDisplay = null
            displayId = -1
        }
    }

    /** Expose the privileged service for IME checks (avoids separate binder connection) */
    fun getPrivilegedService(): IPrivilegedService? = privilegedService

    /**
     * Create a virtual display via Shizuku's elevated privileges. Returns null
     * because the underlying privileged service tracks the [VirtualDisplay] in
     * its own process; locally we only retain the assigned [displayId].
     */
    @Synchronized
    fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid dimensions: ${width}x${height}")
            return null
        }

        val service = privilegedService
        if (service == null) {
            Log.i(TAG, "Shizuku service not bound, cannot create virtual display")
            return null
        }

        return try {
            // Durable breadcrumb BEFORE the call: if system_server/SurfaceFlinger takes
            // the phone down inside it, this is the last line that reaches flash.
            FileLogger.i(TAG, "→ createVirtualDisplay primary ${width}x${height}@${dpi}dpi", durable = true)
            val id = service.createVirtualDisplay(width, height, dpi, "Castla")
            if (id >= 0) {
                // Attach the encoder's Surface so VD content renders into the encoder
                try {
                    service.setSurface(id, surface)
                } catch (e: Exception) {
                    Log.e(TAG, "setSurface failed, releasing VD", e)
                    service.releaseVirtualDisplay(id)
                    displayId = -1
                    return null
                }
                displayId = id
                Log.i(TAG, "Virtual display created via Shizuku: id=$id, ${width}x${height}, surface attached")
                MirrorDiagnostics.log(DiagnosticEvent.VD_CREATED, "id=$id ${width}x${height}")
                null
            } else {
                Log.e(TAG, "Shizuku returned invalid display ID")
                FileLogger.e(TAG, "createVirtualDisplay returned invalid id=$id")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display via Shizuku", e)
            FileLogger.e(TAG, "createVirtualDisplay failed: ${e.javaClass.simpleName}: ${e.message}")
            displayId = -1
            null
        }
    }

    /** Creates an additional virtual display for dual-screen scenarios */
    fun createSecondaryVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        val service = privilegedService
        if (service == null) return -1

        return try {
            FileLogger.i(TAG, "→ createVirtualDisplay secondary ${width}x${height}@${dpi}dpi", durable = true)
            val id = service.createVirtualDisplay(width, height, dpi, "Castla_Sec")
            if (id >= 0) {
                service.setSurface(id, surface)
                FileLogger.i(TAG, "secondary VD created id=$id")
                id
            } else -1
        } catch (e: Exception) {
            FileLogger.e(TAG, "secondary VD create failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    fun releaseSecondaryVirtualDisplay(id: Int) {
        try {
            FileLogger.i(TAG, "→ release secondary VD id=$id", durable = true)
            privilegedService?.releaseVirtualDisplay(id)
        } catch (e: Exception) {
            FileLogger.w(TAG, "release secondary VD id=$id failed: ${e.javaClass.simpleName}")
        }
    }

    fun launchAppOnSpecificDisplay(targetDisplayId: Int, packageName: String) {
        try {
            privilegedService?.launchAppOnDisplay(targetDisplayId, packageName)
        } catch (e: Exception) {}
    }

    fun setSurface(surface: Surface) {
        if (displayId >= 0 && privilegedService != null) {
            try {
                privilegedService?.setSurface(displayId, surface)
                Log.i(TAG, "Surface updated on Virtual Display $displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update surface on VD", e)
            }
        }
    }

    /**
     * Force the virtual display to stay awake/unlocked when the physical screen turns off.
     * Uses PowerManager internal APIs via Shizuku to wake the display and inject user activity.
     */
    fun keepDisplayAwake() {
        val id = displayId
        if (id < 0) return
        try {
            privilegedService?.wakeUpDisplay(id)
            Log.i(TAG, "Forced VD $id display state to ON")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force VD awake", e)
        }
    }

    /**
     * Turn the physical display panel on/off via SurfaceControl (scrcpy approach).
     * When off, device stays awake and VD keeps rendering. Physical screen goes dark.
     * @return true if the call succeeded, false on error or no service
     */
    fun setPhysicalDisplayPower(on: Boolean): Boolean {
        return try {
            val svc = privilegedService ?: run {
                Log.w(TAG, "setPhysicalDisplayPower: no privileged service")
                return false
            }
            // Durable breadcrumb BEFORE the call: SurfaceControl.setDisplayPowerMode
            // behind system_server's back is the riskiest call this app makes. If the
            // phone reboots, this line (and no "result" line after it) is the tell.
            FileLogger.w(TAG, "→ SurfaceControl panel power ${if (on) "ON" else "OFF"} (vd=$displayId)")
            svc.setPhysicalDisplayPower(on)
            Log.i(TAG, "Physical display power: ${if (on) "ON" else "OFF"}")
            FileLogger.i(TAG, "← panel power ${if (on) "ON" else "OFF"} call returned")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setPhysicalDisplayPower failed", e)
            FileLogger.w(TAG, "panel power ${if (on) "ON" else "OFF"} failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Returns true if the privileged service mirror is set. */
    fun isBound(): Boolean = isBound && privilegedService != null

    /** Display ID of the Shizuku-created virtual display, or -1. */
    fun getDisplayId(): Int = displayId

    /** Returns true if a Shizuku virtual display is active. */
    fun hasVirtualDisplay(): Boolean = displayId >= 0 && privilegedService != null

    /** Resize a virtual display by ID without destroying it. */
    @Synchronized
    fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        if (displayId < 0) return false
        return try {
            FileLogger.i(TAG, "→ resize VD $displayId to ${width}x${height}@${dpi}dpi", durable = true)
            privilegedService?.resizeVirtualDisplay(displayId, width, height, dpi)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize VD $displayId", e)
            FileLogger.e(TAG, "resize VD $displayId failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Inject a touch event on the virtual display. */
    /** Inject a complete touchscreen event built by [com.castla.mirror.input.TouchStream]. */
    fun injectMotion(m: com.castla.mirror.input.TouchStream.Motion) {
        val id = displayId
        val svc = privilegedService
        if (id < 0 || svc == null) return
        try {
            svc.injectMotionEvent(id, m.action, m.downTime, m.ids, m.xs, m.ys)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject motion on display $id", e)
        }
    }

    fun injectInput(action: Int, x: Float, y: Float, pointerId: Int) {
        if (displayId < 0) {
            Log.w(TAG, "injectInput skipped: displayId=$displayId")
            return
        }
        val svc = privilegedService
        if (svc == null) {
            Log.w(TAG, "injectInput skipped: privilegedService is null")
            return
        }
        try {
            svc.injectInput(displayId, action, x, y, pointerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input on display $displayId", e)
        }
    }

    /** Launch the home screen on the virtual display. */
    fun launchHomeOnDisplay(): Boolean {
        if (displayId < 0) return false
        return try {
            privilegedService?.launchHomeOnDisplay(displayId)
            Log.i(TAG, "Launched HOME on virtual display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch HOME on virtual display", e)
            false
        }
    }

    /** Launch an app on the virtual display. */
    fun launchAppOnDisplay(packageName: String): Boolean {
        val id = displayId
        if (id < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppOnDisplay(id, packageName)
            Log.i(TAG, "Launched $packageName on virtual display $id")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to launch $packageName on display $id (display not found?)", e)
            displayId = -1
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $id", e)
            false
        }
    }

    /** Launch an app on the virtual display with string intent extra. */
    fun launchAppWithExtraOnDisplay(packageName: String, extraKey: String, extraValue: String): Boolean {
        val id = displayId
        if (id < 0 || packageName.isEmpty()) return false
        return try {
            privilegedService?.launchAppWithExtraOnDisplay(id, packageName, extraKey, extraValue)
            Log.i(TAG, "Launched $packageName with extra on virtual display $id")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to launch $packageName with extra on display $id (display not found?)", e)
            displayId = -1
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName on display $id", e)
            false
        }
    }

    /**
     * Release just the virtual display, keeping the privileged service mirror.
     * Use this when rebuilding the pipeline with new dimensions.
     */
    @Synchronized
    fun releaseVirtualDisplay() {
        val releasedId = displayId
        if (releasedId >= 0) {
            try {
                FileLogger.i(TAG, "→ release primary VD id=$releasedId", durable = true)
                privilegedService?.releaseVirtualDisplay(releasedId)
            } catch (e: android.os.DeadObjectException) {
                // Binder is dead — the remote VD may be orphaned. Log prominently
                // so this is diagnosable; the registerDeathToken safety net in
                // PrivilegedService should handle cleanup.
                Log.e(TAG, "CRITICAL: Binder dead releasing VD id=$releasedId — VD may be orphaned", e)
                MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId binder_dead")
                privilegedService = null
                isBound = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release virtual display", e)
            }
            MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId")
        }
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }

    /**
     * Local-state release. Does NOT unbind the Shizuku user service — that is
     * owned by `ShizukuSetup` for the foreground service lifetime. Releases the
     * privileged-service-side VD first when one is held.
     */
    @Synchronized
    fun release() {
        val releasedId = displayId
        if (releasedId >= 0) {
            try {
                FileLogger.i(TAG, "→ release primary VD id=$releasedId (full release)", durable = true)
                privilegedService?.releaseVirtualDisplay(releasedId)
            } catch (e: android.os.DeadObjectException) {
                Log.e(TAG, "CRITICAL: Binder dead releasing VD id=$releasedId during full release — VD may be orphaned", e)
                MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId binder_dead (full release)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release virtual display", e)
            }
            MirrorDiagnostics.log(DiagnosticEvent.VD_STOPPED, "id=$releasedId (full release)")
        }
        privilegedService = null
        isBound = false
        virtualDisplay?.release()
        virtualDisplay = null
        displayId = -1
    }
}
