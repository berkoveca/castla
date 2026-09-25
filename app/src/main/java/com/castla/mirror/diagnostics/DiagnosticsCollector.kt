package com.castla.mirror.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.castla.mirror.BuildConfig
import com.castla.mirror.network.CloudflareTunnelManager
import com.castla.mirror.service.MirrorForegroundService

/**
 * Assembles the "Copy Recent" diagnostic report: a header answering the usual
 * first questions (which build/device, did the phone reboot, what did the last
 * heartbeat say, why did Android kill us before, is cloudflared alive) followed
 * by the newest part of the persisted log. Call off the main thread.
 */
object DiagnosticsCollector {

    /** Clipboard payloads well past ~100 KB risk TransactionTooLargeException on some devices. */
    const val MAX_CLIPBOARD_CHARS = 60_000

    fun buildReport(context: Context, maxChars: Int = MAX_CLIPBOARD_CHARS, includeLog: Boolean = true): String {
        val tunnel = CloudflareTunnelManager.getInstance(context)
        val sections = listOf(
            "App / device" to appDeviceLines(context),
            "Health now" to listOf(safe { HealthMonitor.snapshot(context, tunnel) }),
            "Mirroring session" to sessionLines(),
            "Reboot check (from previous run)" to CrashBreadcrumbs.summaryLines(),
            "Previous app exits (Android records, newest first)" to safeList { CrashBreadcrumbs.exitReasonLines(context) },
            "Post-mortem (via Shizuku shell)" to PostMortem.summaryLines(),
            "Cloudflare tunnel" to safeList { tunnel.diagnosticSummary() }
        )
        val log = if (includeLog) safe { FileLogger.readRecentTail(maxChars) } else ""
        return DiagnosticReport.build("Castla diagnostic report", sections, log, maxChars)
    }

    private fun appDeviceLines(context: Context): List<String> {
        val boot = CrashBreadcrumbs.currentBoot(context)
        return listOf(
            "app=${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
            "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE} " +
                "sdk=${Build.VERSION.SDK_INT} patch=${Build.VERSION.SECURITY_PATCH}",
            "now=${CrashBreadcrumbs.fmt(System.currentTimeMillis())} uptime=${CrashBreadcrumbs.formatDuration(SystemClock.elapsedRealtime())} " +
                "bootCount=${boot.bootCount ?: "n/a"} bootedAt=${CrashBreadcrumbs.fmt(boot.bootWallMs)}"
        )
    }

    private fun sessionLines(): List<String> = listOf(
        "serviceRunning=${MirrorForegroundService.isServiceRunning} " +
            "cleanupInProgress=${MirrorForegroundService.isCleanupInProgress} " +
            "panelState=${MirrorForegroundService.panelOffStateFlow.value} " +
            "tunnelActive=${MirrorForegroundService.tunnelActiveFlow.value} " +
            "tunnelError=${MirrorForegroundService.tunnelErrorFlow.value ?: "-"}"
    )

    private inline fun safe(block: () -> String): String =
        try { block() } catch (t: Throwable) { "(failed: ${t.javaClass.simpleName}: ${t.message})" }

    private inline fun safeList(block: () -> List<String>): List<String> =
        try { block() } catch (t: Throwable) { listOf("(failed: ${t.javaClass.simpleName}: ${t.message})") }
}
