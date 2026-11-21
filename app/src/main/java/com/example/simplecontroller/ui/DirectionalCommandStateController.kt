package com.example.simplecontroller.ui

internal enum class DirectionalCommandSlot { UP, DOWN, LEFT, RIGHT }

/** Owns each selected directional stage, including its independently cancellable repeat clock. */
internal class DirectionalCommandStateController(
    private val payloadExecutor: ButtonAimPayloadExecutor,
    private val scheduler: ButtonAimPayloadScheduler? = null,
    private val onInvalidPayload: (String) -> Unit = {}
) {
    private data class Selection(val payload: String, val interval: Int?, val stage: String?)
    private class ActivePayload(val selection: Selection) {
        var sustainedLease: ButtonAimPayloadLease? = null
        var repeatedLease: ButtonAimPayloadLease? = null
        var repeat: StickPlusRepeatController? = null
    }

    private val activePayloads = linkedMapOf<DirectionalCommandSlot, ActivePayload>()
    private val finishingLeases = mutableSetOf<ButtonAimPayloadLease>()
    private var generation = 0L

    fun hasRuntimeState(): Boolean = activePayloads.values.any {
        it.sustainedLease != null || it.repeat?.isActive == true
    }

    fun reconcile(
        desiredPayloads: Map<DirectionalCommandSlot, String>,
        repeatIntervalMilliseconds: Int? = null,
        stageKeys: Map<DirectionalCommandSlot, String> = emptyMap()
    ) {
        if (repeatIntervalMilliseconds != null &&
            (repeatIntervalMilliseconds !in 16..1000 || scheduler == null)) {
            clear()
            onInvalidPayload("Stick+ repeat needs a scheduler and an interval from 16 to 1000 milliseconds.")
            return
        }
        val desired = desiredPayloads.mapValues { (slot, payload) ->
            Selection(payload.trim(), repeatIntervalMilliseconds,
                if (repeatIntervalMilliseconds == null) null else stageKeys[slot])
        }.filterValues { it.payload.isNotEmpty() }
        val changed = DirectionalCommandSlot.entries.filter {
            activePayloads[it]?.selection != desired[it]
        }
        if (changed.isEmpty()) return
        val token = generation
        val next = activePayloads.toMutableMap()
        val acquired = mutableListOf<ActivePayload>()

        // Acquire replacements before removing old owners: shared holds must not flicker.
        for (slot in changed) {
            val selection = desired[slot]
            if (selection == null) {
                next.remove(slot)
                continue
            }
            val active = ActivePayload(selection)
            acquired += active
            next[slot] = active
            val error = payloadExecutor.validationError(selection.payload)
            if (error != null) {
                onInvalidPayload(error)
                continue // Remember invalid text; a MOVE stream must not repeatedly try it.
            }
            val tokens = selection.payload.split(Regex("[,\\s]+"))
                .map(String::trim).filter(String::isNotEmpty)
            val repeated = if (selection.interval == null) emptyList() else tokens.filter(::isDigitalHold)
            val sustained = if (selection.interval == null) tokens else tokens.filterNot(::isDigitalHold)
            if (sustained.isNotEmpty()) {
                when (val result = payloadExecutor.activate(ButtonAimPayloadOwner.BASE, sustained.joinToString(","))) {
                    is ButtonAimPayloadActivationResult.Activated -> active.sustainedLease = result.lease
                    is ButtonAimPayloadActivationResult.Invalid -> onInvalidPayload(result.reason)
                    ButtonAimPayloadActivationResult.ReleaseAll -> {
                        stop(acquired)
                        clear()
                        // Do not repeatedly invoke a local release action while still in its zone.
                        activePayloads[slot] = ActivePayload(selection)
                        return
                    }
                }
            }
            if (generation != token) {
                stop(acquired)
                return
            }
            if (repeated.isNotEmpty()) {
                active.repeat = StickPlusRepeatController(checkNotNull(scheduler), press = {
                    when (val result = payloadExecutor.activate(ButtonAimPayloadOwner.BASE, repeated.joinToString(","))) {
                        is ButtonAimPayloadActivationResult.Activated -> {
                            active.repeatedLease = result.lease
                            result.lease != null
                        }
                        else -> false
                    }
                }, release = {
                    payloadExecutor.release(active.repeatedLease)
                    active.repeatedLease = null
                }).also { it.start(checkNotNull(selection.interval)) }
            }
            if (generation != token) {
                stop(acquired)
                return
            }
        }
        stop(changed.mapNotNull { activePayloads[it] }, natural = true)
        if (generation != token) {
            stop(acquired)
            return
        }
        activePayloads.clear()
        activePayloads.putAll(next)
    }

    fun clear(natural: Boolean = false) {
        generation++
        val old = activePayloads.values.toList()
        activePayloads.clear() // Invalidate ownership before any synchronous release callbacks.
        stop(old, natural)
        if (!natural) {
            val finishing = finishingLeases.toTypedArray()
            finishingLeases.clear()
            payloadExecutor.releaseTogether(*finishing)
        }
    }

    private fun stop(values: Collection<ActivePayload>, natural: Boolean = false) {
        values.forEach { it.repeat?.stop() }
        val leases = values.mapNotNull { it.sustainedLease }.toTypedArray()
        if (natural) {
            finishingLeases.addAll(leases)
            payloadExecutor.releaseNormallyTogether(*leases)
            finishingLeases.removeAll { !payloadExecutor.isLeaseActive(it) }
        } else payloadExecutor.releaseTogether(*leases)
    }

    private fun isDigitalHold(token: String): Boolean = DIGITAL_HOLD.matches(token.uppercase())

    private companion object {
        // Pressure LT:/RT:, keyboard, mouse and analog macros deliberately stay sustained.
        val DIGITAL_HOLD = Regex("^X360(?:A|B|X|Y|LB|RB|LT|RT|BACK|VIEW|START|MENU|LS|RS|LSC|RSC|LSTICK|RSTICK|UP|DOWN|LEFT|RIGHT|DPADUP|DPADDOWN|DPADLEFT|DPADRIGHT|GUIDE|SHARE)(?:_HOLD)?$")
    }
}
