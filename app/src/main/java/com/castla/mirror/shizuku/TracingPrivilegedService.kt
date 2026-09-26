package com.castla.mirror.shizuku

import android.os.SystemClock
import com.castla.mirror.diagnostics.FileLogger
import com.castla.mirror.diagnostics.PrivilegedCallTrace
import com.castla.mirror.diagnostics.PrivilegedCallTrace.Level
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps the privileged-service binder so every call Castla makes into
 * system_server is written to the persistent log (rules in [PrivilegedCallTrace]).
 * Risky calls get a durable "→ #n" line before the call and "← #n" after, so if
 * the phone soft-reboots mid-call the log ends on the call that was in flight.
 */
object TracingPrivilegedService {

    private const val TAG = "SysCall"
    private val seq = AtomicLong()
    private val touch = PrivilegedCallTrace.TouchAggregator()

    fun wrap(target: IPrivilegedService): IPrivilegedService {
        if (Proxy.isProxyClass(target.javaClass)) return target
        return Proxy.newProxyInstance(
            IPrivilegedService::class.java.classLoader,
            arrayOf(IPrivilegedService::class.java)
        ) { _, method, args ->
            val name = method.name
            val level = PrivilegedCallTrace.levelOf(name, args)
            if (level == Level.SILENT) return@newProxyInstance invoke(method, target, args)
            if (level == Level.TOUCH) traceTouch(args)

            val n = seq.incrementAndGet()
            val call = if (level == Level.TOUCH) "" else PrivilegedCallTrace.describeCall(name, args)
            if (level == Level.RISKY) FileLogger.i(TAG, "→ #$n $call", durable = true)
            val start = SystemClock.elapsedRealtime()
            try {
                val result = invoke(method, target, args)
                val ms = SystemClock.elapsedRealtime() - start
                when {
                    level == Level.TOUCH -> if (PrivilegedCallTrace.isSlow(ms)) {
                        FileLogger.w(TAG, "slow injectInput ${ms}ms")
                    }
                    PrivilegedCallTrace.isSlow(ms) ->
                        FileLogger.w(TAG, "← #$n $call${PrivilegedCallTrace.describeResult(result)} SLOW ${ms}ms")
                    level != Level.QUIET ->
                        FileLogger.i(TAG, "← #$n $call${PrivilegedCallTrace.describeResult(result)} ${ms}ms")
                }
                result
            } catch (t: Throwable) {
                val ms = SystemClock.elapsedRealtime() - start
                FileLogger.w(TAG, "✗ #$n ${if (call.isEmpty()) name else call} failed after ${ms}ms: " +
                    "${t.javaClass.simpleName}: ${t.message}")
                throw t
            }
        } as IPrivilegedService
    }

    private fun traceTouch(args: Array<out Any?>?) {
        try {
            val a = args ?: return
            val line = touch.onTouch(
                displayId = a[0] as Int, action = a[1] as Int,
                x = a[2] as Float, y = a[3] as Float, pointerId = a[4] as Int,
                nowMs = SystemClock.elapsedRealtime()
            ) ?: return
            FileLogger.i(TAG, line)
        } catch (_: Throwable) { }
    }

    /** Invoke and unwrap reflection so callers still see the original exception type. */
    private fun invoke(method: java.lang.reflect.Method, target: Any, args: Array<out Any?>?): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
}
