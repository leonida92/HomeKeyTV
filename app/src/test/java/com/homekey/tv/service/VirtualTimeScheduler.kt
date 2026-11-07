package com.homekey.tv.service

class VirtualTimeScheduler(
    var currentTime: Long = 1000L
) : TimeScheduler {

    private data class ScheduledTask(
        val executionTime: Long,
        val token: Any,
        val action: () -> Unit
    )

    private val tasks = mutableListOf<ScheduledTask>()

    override fun currentTimeMs(): Long = currentTime

    override fun schedule(delayMs: Long, token: Any, action: () -> Unit) {
        tasks.removeAll { it.token == token }
        tasks.add(ScheduledTask(currentTime + delayMs, token, action))
    }

    override fun cancel(token: Any) {
        tasks.removeAll { it.token == token }
    }

    fun advanceTimeBy(millis: Long) {
        val targetTime = currentTime + millis
        while (true) {
            val nextTask = tasks
                .filter { it.executionTime <= targetTime }
                .minByOrNull { it.executionTime } ?: break

            tasks.remove(nextTask)
            currentTime = nextTask.executionTime
            nextTask.action()
        }
        currentTime = targetTime
    }
}
