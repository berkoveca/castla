package com.castla.mirror.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.castla.mirror.network.CloudflareTunnelManager
import java.io.File
import java.util.Locale

/**
 * One-line device health snapshot for the periodic heartbeat and the
 * clipboard report. Every probe is best-effort: a failing probe prints "?"
 * instead of throwing. Designed so the LAST heartbeat before a reboot says
 * whether the phone was hot, low on memory, or had a runaway cloudflared.
 */
object HealthMonitor {

    fun snapshot(context: Context, tunnel: CloudflareTunnelManager? = null): String {
        val parts = ArrayList<String>()
        parts += thermalPart(context)
        parts += batteryPart(context)
        parts += memoryPart(context)
        parts += "appRss=${rssMb(File("/proc/self/status"))}"
        if (tunnel != null) parts += "tunnel=${tunnel.healthPart()}"
        return parts.joinToString(" ")
    }

    private fun thermalPart(context: Context): String = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DiagnosticNames.thermal(pm.currentThermalStatus)
        } else "n/a"
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // NaN when polled more than once per second or unsupported.
            pm.getThermalHeadroom(10).let { if (it.isNaN()) "n/a" else "%.2f".format(Locale.US, it) }
        } else "n/a"
        "thermal=$status headroom10s=$headroom"
    } catch (_: Throwable) {
        "thermal=?"
    }

    private fun batteryPart(context: Context): String = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) "battery=?" else {
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                0 -> "no"
                else -> "other"
            }
            val temp = if (tempTenths == Int.MIN_VALUE) "?" else "%.1fC".format(Locale.US, tempTenths / 10.0)
            "batteryTemp=$temp battery=${level * 100 / scale}% charging=$plugged"
        }
    } catch (_: Throwable) {
        "battery=?"
    }

    private fun memoryPart(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        "memAvail=${mi.availMem / (1024 * 1024)}MB/${mi.totalMem / (1024 * 1024)}MB " +
            "lowMemory=${mi.lowMemory} lmkThreshold=${mi.threshold / (1024 * 1024)}MB"
    } catch (_: Throwable) {
        "mem=?"
    }

    /** VmRSS of a /proc/<pid>/status file, formatted in MB, or "?". */
    fun rssMb(statusFile: File): String = try {
        DiagnosticNames.parseVmRssKb(statusFile.readText())?.let { "${it / 1024}MB" } ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
