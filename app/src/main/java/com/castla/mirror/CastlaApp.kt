package com.castla.mirror

import android.app.Application
import android.os.Build
import android.os.Process
import com.castla.mirror.diagnostics.CrashBreadcrumbs
import com.castla.mirror.diagnostics.DiagnosticNames
import com.castla.mirror.diagnostics.FileLogger

class CastlaApp : Application() {
    companion object {
        /** Written by the crash handler, consumed by MainActivity on the next start. */
        const val CRASH_MARKER = "last_crash.txt"
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.i("App", "APP_START pid=${Process.myPid()} version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
            "${BuildConfig.BUILD_TYPE} device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})")
        // Must run before any session can start: it reads what the previous process
        // left behind (reboot? died mid-session? last heartbeat?) and then resets it.
        CrashBreadcrumbs.onAppStart(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("UEH", "Uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            // Marker for the next launch: MainActivity offers to copy the crash
            // report, since the in-app log buttons may be on the screen that crashed.
            try {
                java.io.File(filesDir, CRASH_MARKER).writeText(
                    "Uncaught on ${thread.name}\n" + android.util.Log.getStackTraceString(throwable)
                )
            } catch (_: Throwable) {
            }
            // Best-effort: release any live virtual displays synchronously so system_server
            // does not reboot when it later tries to launch home on an orphaned VD.
            try {
                com.castla.mirror.service.MirrorForegroundService.instance?.emergencyReleaseDisplays()
            } catch (_: Throwable) { }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
            }
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Memory pressure is a leading indicator for the low-memory killer taking out
     * this process — and cloudflared with it, since it is our child process.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            FileLogger.i("App", "onTrimMemory ${DiagnosticNames.trimMemory(level)}($level) " +
                "serviceRunning=${com.castla.mirror.service.MirrorForegroundService.isServiceRunning}",
                durable = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        } catch (_: Throwable) { }
    }
}
