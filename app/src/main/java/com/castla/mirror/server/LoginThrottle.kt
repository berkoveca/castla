package com.castla.mirror.server

/**
 * Brute-force protection for the password page: [CLIENT_MAX_FAILURES] wrong
 * passwords lock that client for [CLIENT_LOCK_MS]; [GLOBAL_MAX_FAILURES] wrong
 * passwords from anyone within [GLOBAL_WINDOW_MS] lock ALL logins for
 * [GLOBAL_LOCK_MS] (defeats guessing spread over many IPs).
 */
class LoginThrottle {

    private class Client(var failures: Int = 0, var lockedUntil: Long = 0)

    private val clients = HashMap<String, Client>()
    private val recentFailures = ArrayDeque<Long>()
    private var globalLockedUntil = 0L

    /** Milliseconds this client must still wait (0 = may try). */
    @Synchronized
    fun lockedForMs(key: String, nowMs: Long): Long {
        val client = clients[key]?.lockedUntil ?: 0
        return maxOf(client - nowMs, globalLockedUntil - nowMs, 0)
    }

    @Synchronized
    fun onFailure(key: String, nowMs: Long) {
        val c = clients.getOrPut(key) { Client() }
        c.failures++
        if (c.failures >= CLIENT_MAX_FAILURES) {
            c.lockedUntil = nowMs + CLIENT_LOCK_MS
            c.failures = 0
        }
        recentFailures.addLast(nowMs)
        while (recentFailures.isNotEmpty() && nowMs - recentFailures.first() > GLOBAL_WINDOW_MS) recentFailures.removeFirst()
        if (recentFailures.size >= GLOBAL_MAX_FAILURES) {
            globalLockedUntil = nowMs + GLOBAL_LOCK_MS
            recentFailures.clear()
        }
        if (clients.size > 1000) clients.entries.removeIf { it.value.lockedUntil < nowMs && it.value.failures == 0 }
    }

    @Synchronized
    fun onSuccess(key: String) {
        clients.remove(key)
    }

    companion object {
        const val CLIENT_MAX_FAILURES = 5
        const val CLIENT_LOCK_MS = 15 * 60_000L
        const val GLOBAL_MAX_FAILURES = 20
        const val GLOBAL_WINDOW_MS = 10 * 60_000L
        const val GLOBAL_LOCK_MS = 10 * 60_000L
    }
}
