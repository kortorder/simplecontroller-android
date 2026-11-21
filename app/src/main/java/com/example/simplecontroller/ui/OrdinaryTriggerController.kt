package com.example.simplecontroller.ui

/** Validated ordinary-button trigger token; explicit pulse duration is in seconds. */
internal data class OrdinaryTriggerCommand(val channel: String, val pressure: Float, val durationMs: Long?) {
    companion object {
        fun parse(command: String): OrdinaryTriggerCommand? {
            if (!command.startsWith("LT:", true) && !command.startsWith("RT:", true)) return null
            val match = Regex("(?i)^(LT|RT):([0-9]+(?:\\.[0-9]+)?)(?:P([0-9]+(?:\\.[0-9]+)?))?$")
                .matchEntire(command)
            require(match != null) { "Use LT:0.5 or RT:1.0P0.3 for trigger pressure and pulses." }
            val pressure = match.groupValues[2].toFloatOrNull()
            require(pressure != null && pressure.isFinite() && pressure in 0f..1f) { "Trigger pressure must be between 0 and 1." }
            val seconds = match.groupValues[3].takeIf(String::isNotEmpty)?.toDoubleOrNull()
            require(match.groupValues[3].isEmpty() || seconds != null && seconds.isFinite() && seconds in 0.01..60.0) {
                "Trigger pulse duration must be between 0.01 and 60 seconds."
            }
            return OrdinaryTriggerCommand(match.groupValues[1].uppercase(), pressure, seconds?.let { (it * 1000).toLong() })
        }
    }
}

/**
 * UI-thread-confined trigger ownership. A later token replaces the same channel in
 * this activation only. Normal lift detaches started one-shot pulses; independent
 * activations resolve by MAX until their own deadlines. Cancellation kills all
 * generations before emitting zero. The transport uses a separate child scope so
 * ordinary button releases cannot clear these claims.
 */
internal class OrdinaryTriggerController(
    private val scheduler: ButtonAimPayloadScheduler,
    private val emit: (channel: String, pressure: Float) -> Unit,
    private val changed: () -> Unit = {}
) {
    private class Run(val repeating: Boolean) { val claims = linkedMapOf<String, Claim>() }
    private class Claim(val command: OrdinaryTriggerCommand) {
        var on = true
        var task: ButtonAimScheduledTask? = null
    }
    private val runs = linkedSetOf<Run>()
    private var current: Run? = null
    private val resolved = mutableMapOf("LT" to 0f, "RT" to 0f)
    val hasWork: Boolean get() = runs.any { it.claims.isNotEmpty() }

    fun begin(repeating: Boolean) {
        end()
        // Bound detached work even under pathological input. Fail neutral rather
        // than silently dropping the timeout of a live claim.
        if (runs.size >= 256) cancelAll()
        current = Run(repeating).also { runs.add(it) }
    }

    fun apply(command: OrdinaryTriggerCommand) {
        val run = current ?: return
        run.claims.remove(command.channel)?.task?.cancel()
        if (command.pressure > 0f) {
            val claim = Claim(command)
            run.claims[command.channel] = claim
            refresh()
            if (isCurrent(run, claim) && command.durationMs != null) schedule(run, claim)
        } else refresh()
    }

    /** Only ordinary finger lift: an already-started one-shot pulse may finish. */
    fun end() {
        val run = current ?: return
        current = null
        val ending = run.claims.filterValues { run.repeating || it.command.durationMs == null }.keys
        ending.forEach { run.claims.remove(it)?.task?.cancel() }
        if (run.claims.isEmpty()) runs.remove(run)
        refresh()
    }

    fun cancelAll() {
        val tasks = runs.flatMap { it.claims.values.mapNotNull(Claim::task) }
        runs.clear()
        current = null
        tasks.forEach(ButtonAimScheduledTask::cancel)
        refresh()
    }

    private fun isCurrent(run: Run, claim: Claim) = run in runs && run.claims[claim.command.channel] === claim

    private fun schedule(run: Run, claim: Claim) {
        val duration = claim.command.durationMs ?: return
        val delay = if (run.repeating) {
            if (claim.on) duration / 2 else duration - duration / 2
        } else duration
        claim.task = scheduler.schedule(delay) {
            if (!isCurrent(run, claim)) return@schedule
            claim.task = null
            if (run.repeating) {
                claim.on = !claim.on
                refresh()
                // Delayed delivery changes only one edge, then grants a complete
                // next phase from observation; it never replays missed cycles.
                if (isCurrent(run, claim)) schedule(run, claim)
            } else {
                run.claims.remove(claim.command.channel)
                if (run.claims.isEmpty() && run !== current) runs.remove(run)
                refresh()
            }
        }
    }

    private fun refresh() {
        for (channel in listOf("LT", "RT")) {
            val pressure = runs.maxOfOrNull { it.claims[channel]?.let { c -> if (c.on) c.command.pressure else 0f } ?: 0f } ?: 0f
            if (resolved[channel] != pressure) {
                resolved[channel] = pressure
                emit(channel, pressure)
            }
        }
        changed()
    }
}
