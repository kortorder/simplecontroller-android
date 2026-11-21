package com.example.simplecontroller.ui

/** Ordered ordinary-button commands. Parsing is complete before any command is emitted. */
internal data class OrdinaryPayloadPlan(val steps: List<Step>) {
    sealed interface Step {
        data class Command(val value: String) : Step
        data class Wait(val milliseconds: Long) : Step
    }

    val hasWaitOrPulse: Boolean get() = steps.any {
        it is Step.Wait || it is Step.Command && isPulse(it.value)
    }

    companion object {
        fun parse(payload: String): OrdinaryPayloadPlan {
            require(payload.toByteArray(Charsets.UTF_8).size <= 8192) { "Payload must be at most 8 KiB." }
            val tokens = payload.split(Regex("[,\\s]+")).filter(String::isNotBlank)
            require(tokens.size <= 64) { "Payload must contain at most 64 commands and waits." }
            var totalWait = 0L
            val steps = tokens.mapIndexed { index, token ->
                if (token.startsWith("WAIT_", ignoreCase = true)) {
                    val number = token.substring(5)
                    val value = number.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
                    require(value != null && value in 0..60000) { "Token ${index + 1}: use WAIT_0 through WAIT_60000." }
                    totalWait += value
                    require(totalWait <= 120000) { "Combined waits must not exceed 120 seconds." }
                    Step.Wait(value)
                } else {
                    OrdinaryTriggerCommand.parse(token) // Validate all trigger values before any output.
                    Step.Command(token)
                }
            }
            return OrdinaryPayloadPlan(steps)
        }

        fun isPulse(command: String): Boolean = Regex("(?i)^(LT|RT):[01](?:\\.\\d+)?P[0-9.]+$").matches(command)
    }
}

/**
 * One activation, confined to the UI thread. Every wait starts from its observed
 * callback, so delayed delivery never bursts through several overdue waits.
 * Output ownership/release stays with the caller's existing per-control scope.
 */
internal class OrdinaryPayloadSequence(
    private val scheduler: ButtonAimPayloadScheduler,
    private val emit: (String) -> Unit,
    private val changed: () -> Unit = {}
) {
    private var generation = 0L
    private var pending: ButtonAimScheduledTask? = null
    private var plan = OrdinaryPayloadPlan(emptyList())
    private var next = 0
    private val emitted = mutableListOf<String>()
    val startedCommands: List<String> get() = emitted.toList()
    var isPending = false
        private set

    fun start(value: OrdinaryPayloadPlan) {
        cancelPending()
        plan = value
        next = 0
        emitted.clear()
        advance(generation)
    }

    fun cancelPending() {
        generation++
        pending?.cancel()
        pending = null
        isPending = false
        changed()
    }

    private fun advance(token: Long) {
        while (token == generation && next < plan.steps.size) {
            when (val step = plan.steps[next++]) {
                is OrdinaryPayloadPlan.Step.Command -> {
                    emitted += step.value
                    emit(step.value)
                }
                is OrdinaryPayloadPlan.Step.Wait -> if (step.milliseconds > 0) {
                    isPending = true
                    changed()
                    if (token != generation) return
                    pending = scheduler.schedule(step.milliseconds) {
                        if (token != generation) return@schedule
                        pending = null
                        isPending = false
                        advance(token)
                    }
                    return
                }
            }
        }
        if (token == generation) {
            isPending = false
            changed()
        }
    }
}
