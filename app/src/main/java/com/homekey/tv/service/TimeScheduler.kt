package com.homekey.tv.service

/**
 * Pluggable scheduler for gesture timeouts, debounces, and synthetic repeats.
 * Allows pure, deterministic JVM unit tests without Thread.sleep or flakes.
 */
interface TimeScheduler {
    fun currentTimeMs(): Long
    fun schedule(delayMs: Long, token: Any, action: () -> Unit)
    fun cancel(token: Any)
}
