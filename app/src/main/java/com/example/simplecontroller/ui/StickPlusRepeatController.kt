package com.example.simplecontroller.ui

/**
 * One owned digital stage. A single alternating callback guarantees a real off interval even
 * after a delayed main thread; missed taps are never replayed. Normal finger lift need not stop
 * this controller when its stick is retained. The owning stage must stop it on exit/cancellation.
 */
internal class StickPlusRepeatController(
    private val scheduler: ButtonAimPayloadScheduler,
    private val press: () -> Boolean,
    private val release: () -> Unit
) {
    var isActive = false
        private set
    private var generation = 0L
    private var task: ButtonAimScheduledTask? = null
    private var held = false

    fun start(intervalMilliseconds: Int) {
        require(intervalMilliseconds in 16..1000)
        stop()
        isActive = true
        val token = generation
        val onTime = minOf(50L, intervalMilliseconds.toLong() / 2)
        val offTime = intervalMilliseconds - onTime
        fun scheduleNext(delay: Long) {
            task = scheduler.schedule(delay) {
                if (!isActive || generation != token) return@schedule
                task = null
                if (held) {
                    held = false
                    release()
                    if (isActive && generation == token) scheduleNext(offTime)
                } else {
                    if (!press()) {
                        stop()
                        return@schedule
                    }
                    // A synchronous release-all callback may have invalidated this activation.
                    if (!isActive || generation != token) {
                        release()
                        return@schedule
                    }
                    held = true
                    scheduleNext(onTime)
                }
            }
        }
        if (!press()) {
            stop()
            return
        }
        if (!isActive || generation != token) {
            release()
            return
        }
        held = true
        scheduleNext(onTime)
    }

    fun stop() {
        generation++
        isActive = false
        task?.cancel()
        task = null
        if (held) {
            held = false
            release()
        }
    }
}
