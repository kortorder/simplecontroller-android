package com.example.simplecontroller.net.scx2

import org.junit.Assert.*
import org.junit.Test

/** Same numeric/timing expectations as the frozen iPhone CameraFollowPracticeTests. */
class CameraFollowPracticeMachineTest {
    private fun active(x: Double = .8, y: Double = 0.0): CameraFollowPracticeMachine {
        val m = CameraFollowPracticeMachine()
        m.setEnabled(true, 0); m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(x, y)), 0)
        m.tick(200000); m.tick(300000)
        return m
    }
    private fun x(m: CameraFollowPracticeMachine, expected: Double) = assertEquals(expected, m.snapshot.rightStick.x, 1e-12)

    @Test fun actualDeadlineAtNonzeroUptimeStartsRampWithoutLostWakeup() {
        val origin = 987654321000L
        val m = CameraFollowPracticeMachine(nowMicros = origin)
        m.setEnabled(true, origin); m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), origin)
        val activation = m.nextDeadlineMicros!!; assertEquals(origin + 200000, activation)
        m.tick(activation); x(m, 0.0)
        val ramp = m.nextDeadlineMicros!!; assertEquals(activation + 10000, ramp)
        m.tick(ramp); x(m, .025)
    }

    @Test fun disabledPreservesManualInputWithoutAssistance() {
        val m = CameraFollowPracticeMachine()
        val right = CameraFollowVector(.7, -.4)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0), manualRightStick = right), 0)
        m.tick(1000000)
        assertEquals(right, m.snapshot.rightStick); assertEquals(CameraFollowOutputSource.MANUAL, m.snapshot.source)
        assertEquals(CameraFollowVector(), m.snapshot.generatedRightStick); assertNull(m.nextDeadlineMicros)
    }

    @Test fun strictMovementDeadzoneAndBriefMovementCannotActivate() {
        val m = CameraFollowPracticeMachine(); m.setEnabled(true, 0)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(.2)), 0); m.tick(1000000)
        x(m, 0.0); assertNull(m.nextDeadlineMicros)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(.8)), 1000000)
        m.tick(1199000); x(m, 0.0)
        m.updateInputs(CameraFollowInputs(), 1199000); m.tick(3000000); x(m, 0.0)
    }

    @Test fun activationDelayNeverAccumulatesRampTime() {
        val m = CameraFollowPracticeMachine(); m.setEnabled(true, 0)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), 0)
        assertEquals(200000L, m.nextDeadlineMicros)
        m.tick(200000); x(m, 0.0); assertEquals(210000L, m.nextDeadlineMicros)
        m.tick(210000); x(m, .025); m.tick(380000); x(m, .45); assertNull(m.nextDeadlineMicros)
    }

    @Test fun horizontalResponseCeilingAndReversalMatchSource() {
        val m = active(.4); x(m, .22); assertEquals(0.0, m.snapshot.rightStick.y, 0.0)
        assertEquals(CameraFollowOutputSource.CAMERA_FOLLOW, m.snapshot.source)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(-1.0)), 300000)
        m.tick(1000000); x(m, -.45)
    }

    @Test fun centeringRampsOutButExclusionClearsImmediately() {
        val m = active(); val before = m.snapshot.rightStick.x
        m.updateInputs(CameraFollowInputs(), 300000); m.tick(310000)
        assertTrue(m.snapshot.rightStick.x in 0.0..<before)
        m.tick(500000); x(m, 0.0)
        val excluded = active()
        excluded.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0), leftAllowsFollow = false), 310000)
        x(excluded, 0.0); assertEquals(1.0, excluded.inputs.leftStick.x, 0.0)
        assertTrue(excluded.requestedEnabled); assertNull(excluded.nextDeadlineMicros)
    }

    @Test fun eligibleWinnerAfterExcludedSourceGetsFreshActivationDelay() {
        val m = active()
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0), leftAllowsFollow = false), 310000)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), 500000)
        m.tick(699000); x(m, 0.0); m.tick(710000); x(m, 0.0)
        m.tick(720000); assertTrue(m.snapshot.rightStick.x > 0)
    }

    @Test fun manualPriorityAndExactReleaseTimeout() {
        val m = active(); val right = CameraFollowVector(.7, .2)
        m.updateInputs(m.inputs.copy(manualRightStick = right), 310000)
        assertEquals(right, m.snapshot.rightStick); assertEquals(CameraFollowOutputSource.MANUAL, m.snapshot.source)
        m.updateInputs(m.inputs.copy(manualRightStick = CameraFollowVector()), 400000)
        m.tick(1149999); assertEquals(CameraFollowOutputSource.MANUAL_OVERRIDE, m.snapshot.source)
        assertEquals(1150000L, m.nextDeadlineMicros)
        m.tick(m.nextDeadlineMicros!!); x(m, 0.0)
        m.tick(1250000); assertTrue(m.snapshot.rightStick.x > 0)
    }

    @Test fun macroWinsManualAndFollowWithoutChangingInputs() {
        val m = active(); val right = CameraFollowVector(.6, .2); val macro = CameraFollowVector(-1.0, .5)
        val input = m.inputs.copy(manualRightStick = right, macroRightStick = macro)
        m.updateInputs(input, 310000)
        assertEquals(input, m.inputs); assertEquals(macro, m.snapshot.rightStick)
        assertEquals(CameraFollowOutputSource.MACRO, m.snapshot.source)
        m.updateInputs(input.copy(macroRightStick = CameraFollowVector()), 320000)
        assertEquals(right, m.snapshot.rightStick); assertEquals(CameraFollowOutputSource.MANUAL, m.snapshot.source)
    }

    @Test fun neutralManualContactSuppressesAndRestartsDelayAfterLift() {
        val m = active(); m.updateInputs(m.inputs.copy(manualRightContactActive = true), 310000)
        assertTrue(m.requestedEnabled); assertFalse(m.snapshot.effectiveEnabled); assertTrue(m.snapshot.suppressed)
        assertEquals(CameraFollowOutputSource.SUPPRESSED, m.snapshot.source); x(m, 0.0)
        m.updateInputs(m.inputs.copy(manualRightContactActive = false), 1000000)
        m.tick(1199000); x(m, 0.0); m.tick(1210000); m.tick(1220000)
        assertTrue(m.snapshot.rightStick.x > 0)
    }

    @Test fun mouseAndManualOwnersMustBothClearAndToggleDoesNotClearSuppression() {
        val m = active()
        m.updateInputs(m.inputs.copy(mouseSuppressed = true, manualRightContactActive = true), 310000)
        m.updateInputs(m.inputs.copy(manualRightContactActive = false), 400000)
        m.setEnabled(false, 500000); m.setEnabled(true, 600000); m.tick(2000000)
        assertFalse(m.snapshot.effectiveEnabled); assertTrue(m.snapshot.mouseSuppressed); x(m, 0.0)
        m.updateInputs(m.inputs.copy(mouseSuppressed = false), 2000000)
        assertTrue(m.snapshot.effectiveEnabled); assertEquals(2200000L, m.nextDeadlineMicros)
    }

    @Test fun liveStrengthChangesScaleGainCeilingAndImmediateReduction() {
        val m = active(1.0); m.tick(500000)
        m.setStrengthPercent(125.0, 500000); x(m, .5625)
        m.setStrengthPercent(75.0, 500000); x(m, .3375)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(.4)), 500000)
        m.setStrengthPercent(125.0, 500000); m.tick(1000000); x(m, .275)
    }

    @Test fun zeroStrengthKeepsToggleAndManualInput() {
        val m = active(); m.setStrengthPercent(0.0, 300000)
        assertTrue(m.requestedEnabled); assertEquals(CameraFollowVector(), m.snapshot.generatedRightStick)
        assertNull(m.nextDeadlineMicros)
        m.updateInputs(m.inputs.copy(manualRightStick = CameraFollowVector(.8)), 400000); x(m, .8)
    }

    @Test fun duplicateEnableDoesNotResetAndDisableClearsRamp() {
        val m = active(); val before = m.snapshot.generatedRightStick
        m.setEnabled(true, 300000); assertEquals(before, m.snapshot.generatedRightStick)
        m.toggle(310000); assertFalse(m.requestedEnabled); assertEquals(CameraFollowVector(), m.snapshot.generatedRightStick)
        m.setEnabled(false, 500000); x(m, 0.0)
    }

    @Test fun clearInputsKeepsToggleButLifecycleResetDisarmsAndPreservesStrength() {
        val m = active(); m.clearInputs(400000)
        assertTrue(m.requestedEnabled); x(m, 0.0); assertNull(m.nextDeadlineMicros)
        m.setStrengthPercent(125.0, 400000); m.reset(500000)
        assertFalse(m.requestedEnabled); assertEquals(125.0, m.strengthPercent, 0.0)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), 600000)
        m.tick(4000000); x(m, 0.0)
    }

    @Test fun veryLateTickCannotReplayActivationRampOrOverflow() {
        val m = CameraFollowPracticeMachine(); m.setEnabled(true, 0)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), 0)
        m.tick(100000000); x(m, 0.0); assertEquals(100010000L, m.nextDeadlineMicros)
        m.tick(m.nextDeadlineMicros!!); x(m, .025)
        m.tick(Long.MAX_VALUE - 120000000); x(m, .45)
    }

    @Test fun invalidValuesAndClockRejectBeforeMutation() {
        val m = active(); val before = m.snapshot
        assertThrows(IllegalArgumentException::class.java) { m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(Double.NaN)), 10000000) }
        assertThrows(IllegalArgumentException::class.java) { m.updateInputs(CameraFollowInputs(macroRightStick = CameraFollowVector(y = 1.01)), 10000000) }
        assertThrows(IllegalArgumentException::class.java) { m.setStrengthPercent(Double.POSITIVE_INFINITY, 10000000) }
        assertThrows(IllegalArgumentException::class.java) { m.setStrengthPercent(201.0, 10000000) }
        assertThrows(IllegalArgumentException::class.java) { m.tick(100000) }
        assertThrows(IllegalArgumentException::class.java) { m.tick(Long.MAX_VALUE) }
        assertEquals(before, m.snapshot); m.tick(310000)
    }

    @Test fun alternativeTuningAndSidewaysGateRemainBounded() {
        val m = CameraFollowPracticeMachine(CameraFollowTuning(rampInMilliseconds = 0, rampOutMilliseconds = 0,
            invertDirection = true, allowSidewaysSteering = false))
        m.setEnabled(true, 0); m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0)), 0)
        m.tick(1000000); x(m, 0.0)
        m.updateInputs(CameraFollowInputs(leftStick = CameraFollowVector(1.0, .5)), 1000000); x(m, -.45)
        assertThrows(IllegalArgumentException::class.java) { CameraFollowPracticeMachine(CameraFollowTuning(gain = Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { CameraFollowPracticeMachine(CameraFollowTuning(rampInMilliseconds = -1)) }
    }

    @Test fun neutralMacroReservationBlocksCameraAndDoesNotInventManualOverride() {
        val m = active(); m.updateInputs(m.inputs.copy(macroRightReserved = true), 310000)
        assertEquals(CameraFollowOutputSource.MACRO, m.snapshot.source); x(m, 0.0)
        m.tick(1000000); x(m, 0.0)
        m.updateInputs(m.inputs.copy(macroRightReserved = false), 1100000)
        assertEquals(CameraFollowOutputSource.NEUTRAL, m.snapshot.source)
        assertEquals(1110000L, m.nextDeadlineMicros)
        m.tick(m.nextDeadlineMicros!!); x(m, .025)
    }

    @Test fun pureSnapshotNeverMutatesCallerControllerOutput() {
        val physical = Scx2Output(leftX = 32767, leftTrigger = 32768, rightTrigger = 45875)
        val m = active(physical.leftX / 32767.0); m.tick(500000)
        val practice = physical.copy(rightX = Scx2Output.axis(m.snapshot.rightStick.x))
        assertTrue(practice.rightX > 0); assertEquals(0, physical.rightX)
        assertEquals(physical.leftTrigger, practice.leftTrigger); assertEquals(physical.rightTrigger, practice.rightTrigger)
    }
}
