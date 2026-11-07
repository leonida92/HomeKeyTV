package com.homekey.tv.service

import android.os.Handler
import android.os.SystemClock

/**
 * Production implementation of TimeScheduler delegating to Android Handler and SystemClock.
 */
class AndroidTimeScheduler(
    private val handler: Handler
) : TimeScheduler {

    private val scheduledRunnables = mutableMapOf<Any, Runnable>()

    override fun currentTimeMs(): Long = SystemClock.uptimeMillis()

    @Synchronized
    override fun schedule(delayMs: Long, token: Any, action: () -> Unit) {
        cancel(token)
        val runnable = Runnable {
            synchronized(this@AndroidTimeScheduler) {
                scheduledRunnables.remove(token)
            }
            action()
        }
        scheduledRunnables[token] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    @Synchronized
    override fun cancel(token: Any) {
        scheduledRunnables.remove(token)?.let {
            handler.removeCallbacks(it)
        }
    }
}
