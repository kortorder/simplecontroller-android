package com.example.simplecontroller.net.scx2

import com.example.simplecontroller.net.PhoneOutputCoordinator
import com.example.simplecontroller.net.PhoneAnalogOwnership
import org.junit.Assert.*
import org.junit.Test

class PracticeCameraProjectionTest {
    private class Fixture {
        val canonical = Scx2CanonicalOutput()
        val projection = PracticeCameraProjection()
        var sequence = 0L
        val coordinator = PhoneOutputCoordinator("camera", { ++sequence }, { packet ->
            assertTrue(canonical.acceptFrame(packet.command) {})
            packet.onConfirmed(true)
        }).apply { unifiedAnalogChannels = true }
        var enabled = true
        var strength = 100.0
        var mouse = false
        fun owner(name: String) = coordinator.beginScope(name, "player1")
        fun sync(now: Long) = projection.update(canonical.state, coordinator.resolvedAnalogOwnership(),
            enabled, strength, false, mouse, now)
        fun movingLeft() = owner("left").also { it.sendStick("LS", 1f, 0f); sync(0); sync(200_000); sync(400_000) }
    }

    @Test fun realOwnedLeftMovementGeneratesPracticeRightOnly() {
        val f = Fixture(); f.movingLeft()
        assertTrue(f.projection.snapshot.generatedRightStick.x > 0)
        assertTrue(f.projection.output.gamepad.rightX > 0)
        assertEquals(0, f.canonical.state.gamepad.rightX)
        assertEquals(32767, f.projection.output.gamepad.leftX)
        assertEquals(CameraFollowOutputSource.CAMERA_FOLLOW, f.projection.snapshot.source)
    }

    @Test fun liveRightContactAtCenterPausesAndRequiresFreshActivationOnLift() {
        val f = Fixture(); f.movingLeft(); val right = f.owner("right")
        right.sendStick("RS", 0f, 0f); f.sync(410_000)
        assertTrue(f.projection.snapshot.requestedEnabled); assertFalse(f.projection.snapshot.effectiveEnabled)
        assertEquals(0, f.projection.output.gamepad.rightX)
        right.finish(); f.sync(420_000); f.sync(610_000)
        assertEquals(0.0, f.projection.snapshot.generatedRightStick.x, 0.0)
        f.sync(620_000); f.sync(800_000)
        assertTrue(f.projection.output.gamepad.rightX > 0)
    }

    @Test fun winningLeftExclusionChangesImmediatelyWithoutChangingSavedMovement() {
        val f = Fixture(); val left = f.movingLeft()
        left.sendStick("LS", 1f, 0f, exclude = true); f.sync(410_000)
        assertFalse(f.coordinator.resolvedAnalogOwnership().leftAllowsFollow)
        assertEquals(0, f.projection.output.gamepad.rightX)
        assertEquals(32767, f.canonical.state.gamepad.leftX)
    }

    @Test fun macroOffGapReservesRightWithoutInventingManualOverride() {
        val f = Fixture(); f.movingLeft(); val macro = f.owner("macro")
        assertTrue(macro.reservePayload("repeat", "RS:R100"))
        assertTrue(macro.acquirePayload("repeat", "RS:R100", exclusive = true))
        f.sync(410_000); assertEquals(32767, f.projection.output.gamepad.rightX)
        macro.releasePayload("repeat"); f.sync(420_000)
        assertEquals(0, f.projection.output.gamepad.rightX)
        assertEquals(CameraFollowOutputSource.MACRO, f.projection.snapshot.source)
        macro.endPayload("repeat"); f.sync(430_000); f.sync(620_000)
        assertTrue(f.projection.output.gamepad.rightX > 0)
        assertNotEquals(CameraFollowOutputSource.MANUAL_OVERRIDE, f.projection.snapshot.source)
    }

    @Test fun retainedSmallActualRightOutputIsNeverOverwrittenByGeneratedCamera() {
        val f = Fixture(); f.movingLeft(); val right = f.owner("right")
        right.sendStick("RS", 0.05f, 0f); right.setRetained(true); f.sync(410_000); f.sync(800_000)
        assertEquals(f.canonical.state.gamepad.rightX, f.projection.output.gamepad.rightX)
        assertEquals(CameraFollowOutputSource.MACRO, f.projection.snapshot.source)
    }

    @Test fun stickyMouseSuppressionAndReleaseResetKeepPhysicalStateSeparate() {
        val f = Fixture(); f.movingLeft(); f.mouse = true; f.sync(410_000)
        assertTrue(f.projection.snapshot.mouseSuppressed); assertEquals(0, f.projection.output.gamepad.rightX)
        f.enabled = false; f.sync(420_000); f.enabled = true; f.sync(430_000)
        assertEquals(0, f.projection.output.gamepad.rightX)
        f.projection.reset(440_000); f.projection.tick(2_000_000)
        assertFalse(f.projection.snapshot.requestedEnabled)
        assertNull(f.projection.nextDeadlineMicros)
        assertEquals(Scx2PracticeOutput(), f.projection.output)
    }

    @Test fun removedOwnerMetadataCannotCreateFalseMovementDuringMultiKeyCleanup() {
        val projection = PracticeCameraProjection()
        val old = Scx2PracticeOutput(gamepad = Scx2Output(leftX = 32767, rightX = 32767))
        // Coordinator removes all owners before publishing individual key releases.
        projection.update(old, PhoneAnalogOwnership(), true, 100.0, false, false, 0)
        projection.tick(1_000_000)
        assertEquals(CameraFollowOutputSource.NEUTRAL, projection.snapshot.source)
        assertEquals(0.0, projection.snapshot.generatedRightStick.x, 0.0)
        assertEquals(0, projection.output.gamepad.rightX)
        assertNull(projection.nextDeadlineMicros)
    }
}
