package com.castla.mirror

import android.app.Application
import com.castla.mirror.diagnostics.FileLogger

class CastlaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("UEH", "Uncaught on ${thread.name}", throwable)
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
}
