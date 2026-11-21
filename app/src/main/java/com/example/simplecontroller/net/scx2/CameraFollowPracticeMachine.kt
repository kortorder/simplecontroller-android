package com.example.simplecontroller.net.scx2

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure local camera assistance. This file never sends a controller/network command. */
data class CameraFollowVector(val x: Double = 0.0, val y: Double = 0.0) {
    val valid get() = x.isFinite() && y.isFinite() && x in -1.0..1.0 && y in -1.0..1.0
}

/** Audited original receiver defaults, matching the verified iPhone machine. */
data class CameraFollowTuning(
    val movementDeadzone: Double = 0.20,
    val manualRightDeadzone: Double = 0.15,
    val activationDelayMilliseconds: Int = 200,
    val manualOverrideMilliseconds: Int = 750,
    val gain: Double = 0.55,
    val maximumOutput: Double = 0.45,
    val rampInMilliseconds: Int = 180,
    val rampOutMilliseconds: Int = 120,
    val invertDirection: Boolean = false,
    val allowSidewaysSteering: Boolean = true
) {
    fun validate() {
        require(movementDeadzone.isFinite() && movementDeadzone in 0.0..1.0 &&
            manualRightDeadzone.isFinite() && manualRightDeadzone in 0.0..1.0 &&
            gain.isFinite() && gain in 0.0..5.0 && maximumOutput.isFinite() && maximumOutput in 0.0..1.0 &&
            listOf(activationDelayMilliseconds, manualOverrideMilliseconds, rampInMilliseconds, rampOutMilliseconds)
                .all { it in 0..60000 }) { "Camera Follow timing, gain or deadzone is invalid." }
    }
}

/** Already-arbitrated inputs. Ownership flags must come from owners, not merely axes. */
data class CameraFollowInputs(
    val leftStick: CameraFollowVector = CameraFollowVector(),
    val leftAllowsFollow: Boolean = true,
    val manualRightStick: CameraFollowVector = CameraFollowVector(),
    val macroRightStick: CameraFollowVector = CameraFollowVector(),
    val manualRightContactActive: Boolean = false,
    val mouseSuppressed: Boolean = false,
    val macroRightReserved: Boolean = false
) {
    fun validate() {
        require(listOf(leftStick, manualRightStick, macroRightStick).all { it.valid }) {
            "Camera Follow inputs must be finite normalized stick positions."
        }
    }
}

enum class CameraFollowOutputSource { MACRO, MANUAL, MANUAL_OVERRIDE, CAMERA_FOLLOW, NEUTRAL, SUPPRESSED }

data class CameraFollowPracticeSnapshot(
    val requestedEnabled: Boolean,
    val effectiveEnabled: Boolean,
    val strengthPercent: Double,
    val manualRightContactActive: Boolean,
    val mouseSuppressed: Boolean,
    val generatedRightStick: CameraFollowVector,
    val rightStick: CameraFollowVector,
    val source: CameraFollowOutputSource,
    val nextDeadlineMicros: Long?
) {
    val suppressed get() = manualRightContactActive || mouseSuppressed
}

/**
 * Deterministic port of the iPhone Practice machine. Integer monotonic microseconds
 * keep reported activation/override deadlines identical to their threshold tests.
 * Integration must only project this result into Practice, never into SCX2 state.
 * Sticky mouse-owner lifetime belongs to the caller; disabling Follow does not
 * erase suppression, and a neutral reserved macro still owns the right channel.
 */
class CameraFollowPracticeMachine(val tuning: CameraFollowTuning = CameraFollowTuning(), nowMicros: Long = 0) {
    var inputs = CameraFollowInputs(); private set
    var requestedEnabled = false; private set
    var strengthPercent = 100.0; private set
    private var effectiveEnabled = false
    private var movementStartedAt: Long? = null
    private var manualOverrideUntil = 0L
    private var generatedX = 0.0
    private var lastTarget = 0.0
    private var lastStepAt = nowMicros
    private var lastTime = nowMicros
    private var selectedRightStick = CameraFollowVector()
    private var selectedSource = CameraFollowOutputSource.MANUAL

    init { tuning.validate(); validateTime(nowMicros) }

    val snapshot get() = CameraFollowPracticeSnapshot(requestedEnabled, effectiveEnabled, strengthPercent,
        inputs.manualRightContactActive, inputs.mouseSuppressed, CameraFollowVector(generatedX),
        selectedRightStick, selectedSource, nextDeadlineMicros)

    val nextDeadlineMicros: Long? get() {
        val deadlines = mutableListOf<Long>()
        if (!macroActive && !manualActive && manualOverrideUntil > lastTime) deadlines += manualOverrideUntil
        val start = movementStartedAt
        if (effectiveEnabled && !macroActive && !manualActive && leftIsMoving && start != null) {
            val activation = start + tuning.activationDelayMilliseconds * 1000L
            if (activation > lastTime) deadlines += max(activation, manualOverrideUntil)
        }
        if (abs(cameraTarget(lastTime) - generatedX) > 1e-12) deadlines += lastTime + 10000
        return deadlines.filter { it > lastTime }.minOrNull()
    }

    fun setEnabled(enabled: Boolean, nowMicros: Long) {
        acceptTime(nowMicros); requestedEnabled = enabled
        synchronizeEffectiveState(nowMicros); step(nowMicros)
    }
    fun toggle(nowMicros: Long) = setEnabled(!requestedEnabled, nowMicros)

    fun setStrengthPercent(percent: Double, nowMicros: Long) {
        require(percent.isFinite() && percent in 0.0..200.0) { "Camera Follow strength must be 0–200 percent." }
        acceptTime(nowMicros)
        if (strengthPercent != percent) {
            val old = strengthPercent; strengthPercent = percent
            generatedX = clamp(if (old > 0) generatedX / old * percent else 0.0)
            val target = cameraTarget(nowMicros)
            if (abs(generatedX) > abs(target)) generatedX = target
            lastTarget = if (old > 0) lastTarget / old * percent else 0.0
            lastStepAt = nowMicros
        }
        step(nowMicros)
    }

    fun updateInputs(value: CameraFollowInputs, nowMicros: Long) {
        value.validate(); acceptTime(nowMicros)
        val wasMoving = leftIsMoving; val wasManual = manualActive
        inputs = value
        if (!value.leftAllowsFollow) {
            movementStartedAt = null; generatedX = 0.0; lastTarget = 0.0; lastStepAt = nowMicros
        } else if (leftIsMoving && !wasMoving) movementStartedAt = nowMicros
        else if (!leftIsMoving) movementStartedAt = null
        if (wasManual && !manualActive) manualOverrideUntil = nowMicros + tuning.manualOverrideMilliseconds * 1000L
        synchronizeEffectiveState(nowMicros); step(nowMicros)
    }

    fun tick(nowMicros: Long) { acceptTime(nowMicros); step(nowMicros) }

    /** Receiver clear-input behavior keeps the toggle/strength; fresh movement must activate. */
    fun clearInputs(nowMicros: Long) {
        acceptTime(nowMicros); inputs = CameraFollowInputs(); movementStartedAt = null; manualOverrideUntil = 0
        generatedX = 0.0; lastTarget = 0.0; lastStepAt = nowMicros
        effectiveEnabled = requestedEnabled; step(nowMicros)
    }

    /** App lifecycle/profile/Release all boundary also disarms the requested toggle. */
    fun reset(nowMicros: Long) { acceptTime(nowMicros); requestedEnabled = false; clearInputs(nowMicros) }

    private val maximumOutput get() = min(1.0, tuning.maximumOutput * strengthPercent / 100)
    private val leftIsMoving get() = inputs.leftAllowsFollow && hypot(inputs.leftStick.x, inputs.leftStick.y) > tuning.movementDeadzone
    private val manualActive get() = hypot(inputs.manualRightStick.x, inputs.manualRightStick.y) > tuning.manualRightDeadzone
    private val macroActive get() = inputs.macroRightReserved || hypot(inputs.macroRightStick.x, inputs.macroRightStick.y) > 0.0001

    private fun synchronizeEffectiveState(now: Long) {
        val enabled = requestedEnabled && !inputs.manualRightContactActive && !inputs.mouseSuppressed
        if (enabled == effectiveEnabled) return
        effectiveEnabled = enabled; generatedX = 0.0; lastTarget = 0.0; lastStepAt = now
        movementStartedAt = if (enabled && leftIsMoving) now else null
    }

    private fun cameraTarget(now: Long): Double {
        val start = movementStartedAt ?: return 0.0
        if (!effectiveEnabled || macroActive || manualActive || now < manualOverrideUntil || !leftIsMoving ||
            now < start + tuning.activationDelayMilliseconds * 1000L ||
            !tuning.allowSidewaysSteering && abs(inputs.leftStick.y) <= tuning.movementDeadzone) return 0.0
        return clamp(inputs.leftStick.x * tuning.gain * strengthPercent / 100 * if (tuning.invertDirection) -1 else 1)
    }

    private fun step(now: Long) {
        var elapsedMicros = (now - lastStepAt).toDouble(); lastStepAt = now
        val target = cameraTarget(now)
        // Waiting for activation never becomes accumulated ramp time.
        if (abs(target) > 0.0001 && abs(lastTarget) <= 0.0001) elapsedMicros = 0.0
        val ramp = if (abs(target) > abs(generatedX)) tuning.rampInMilliseconds else tuning.rampOutMilliseconds
        if (ramp <= 0) generatedX = target
        else {
            val delta = maximumOutput * min(elapsedMicros, 2.0 * ramp * 1000) / (ramp * 1000)
            generatedX = if (generatedX < target) min(generatedX + delta, target) else max(generatedX - delta, target)
        }
        generatedX = clamp(generatedX); lastTarget = target
        when {
            macroActive -> { selectedRightStick = inputs.macroRightStick; selectedSource = CameraFollowOutputSource.MACRO }
            manualActive -> { selectedRightStick = inputs.manualRightStick; selectedSource = CameraFollowOutputSource.MANUAL }
            now < manualOverrideUntil -> { selectedRightStick = inputs.manualRightStick; selectedSource = CameraFollowOutputSource.MANUAL_OVERRIDE }
            effectiveEnabled && abs(generatedX) > 0.0001 -> { selectedRightStick = CameraFollowVector(generatedX); selectedSource = CameraFollowOutputSource.CAMERA_FOLLOW }
            !effectiveEnabled -> { selectedRightStick = inputs.manualRightStick; selectedSource = if (requestedEnabled) CameraFollowOutputSource.SUPPRESSED else CameraFollowOutputSource.MANUAL }
            else -> { selectedRightStick = CameraFollowVector(); selectedSource = CameraFollowOutputSource.NEUTRAL }
        }
    }

    private fun clamp(value: Double) = value.coerceIn(-maximumOutput, maximumOutput)
    private fun acceptTime(now: Long) { validateTime(now); require(now >= lastTime) { "Camera Follow time cannot move backwards." }; lastTime = now }
    private fun validateTime(now: Long) { require(now in 0..Long.MAX_VALUE - 120_000_000) { "Camera Follow time is out of range." } }
}
