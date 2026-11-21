package com.example.simplecontroller.net.scx2

import com.example.simplecontroller.net.PhoneAnalogOwnership

/** Actual local-output integration: projection changes Practice only, never canonical wire state. */
class PracticeCameraProjection(nowMicros: Long = 0) {
    private val machine = CameraFollowPracticeMachine(nowMicros = nowMicros)
    private var base = Scx2PracticeOutput()
    val snapshot get() = machine.snapshot
    val nextDeadlineMicros get() = machine.nextDeadlineMicros
    val output: Scx2PracticeOutput get() = base.copy(gamepad = base.gamepad.copy(
        rightX = Scx2Output.axis(snapshot.rightStick.x), rightY = Scx2Output.axis(snapshot.rightStick.y)))

    fun update(base: Scx2PracticeOutput, ownership: PhoneAnalogOwnership,
               enabled: Boolean, strengthPercent: Double, manualRightContact: Boolean,
               mouseSuppressed: Boolean, nowMicros: Long) {
        this.base = base
        machine.setEnabled(enabled, nowMicros)
        machine.setStrengthPercent(strengthPercent, nowMicros)
        val right = if (ownership.rightOccupied)
            CameraFollowVector(base.gamepad.rightX / 32767.0, base.gamepad.rightY / 32767.0) else CameraFollowVector()
        val manualContact = manualRightContact || ownership.rightLiveManual
        // Retained or macro output reserves its real channel even at zero or below the manual deadzone.
        val reserved = ownership.rightOccupied && (!ownership.rightLiveManual || ownership.rightMacro || ownership.rightRetained)
        machine.updateInputs(CameraFollowInputs(
            leftStick = if (ownership.leftOccupied)
                CameraFollowVector(base.gamepad.leftX / 32767.0, base.gamepad.leftY / 32767.0) else CameraFollowVector(),
            leftAllowsFollow = ownership.leftAllowsFollow,
            manualRightStick = if (reserved) CameraFollowVector() else right,
            macroRightStick = if (reserved) right else CameraFollowVector(),
            manualRightContactActive = manualContact, mouseSuppressed = mouseSuppressed,
            macroRightReserved = reserved), nowMicros)
    }

    fun tick(nowMicros: Long) { machine.tick(nowMicros) }
    fun reset(nowMicros: Long) { base = Scx2PracticeOutput(); machine.reset(nowMicros) }
}
