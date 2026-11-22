package com.example.simplecontroller.net.scx2

import com.example.simplecontroller.net.PhoneOutputCoordinator
import org.junit.Assert.*
import org.junit.Test

class Scx2CanonicalOutputTest {
    private class Fixture {
        val output = Scx2CanonicalOutput()
        val emitted = mutableListOf<Scx2PracticeOutput>()
        val timers = mutableListOf<() -> Unit>()
        var sequence = 0L
        val coordinator = PhoneOutputCoordinator("test", { ++sequence }, { packet ->
            assertTrue(packet.isCurrent())
            assertTrue(output.acceptFrame(packet.command, emitted::add))
            packet.onConfirmed(true)
        }, { _, task -> timers.add(task) }).apply { unifiedAnalogChannels = true }
        fun owner(name: String) = coordinator.beginScope(name, "player1")
    }

    @Test fun independentPressureOwnersRestoreTheLowerSurvivor() {
        val f = Fixture(); val aim = f.owner("aim"); val fire = f.owner("fire")
        aim.sendCommand("LT:0.5"); aim.sendCommand("RT:0.2"); fire.sendCommand("RT:0.8")
        assertEquals(32768, f.output.state.gamepad.leftTrigger)
        assertEquals(52428, f.output.state.gamepad.rightTrigger)
        fire.finish(); assertEquals(13107, f.output.state.gamepad.rightTrigger)
        aim.finish(); assertEquals(Scx2Output.NEUTRAL, f.output.state.gamepad)
    }

    @Test fun forceReleaseAndPulseExpiryPreserveIndependentDigitalOwner() {
        val f = Fixture(); val held = f.owner("held"); val pulse = f.owner("pulse")
        held.sendCommand("X360A_HOLD"); pulse.sendCommand("X360A")
        f.timers.forEach { it() }
        assertEquals(1, f.output.state.gamepad.buttons)
        pulse.sendLegacyForceRelease("X360A_RELEASE")
        assertEquals(1, f.output.state.gamepad.buttons)
        held.finish(); assertEquals(0, f.output.state.gamepad.buttons)
    }

    @Test fun repeatReplayPreservesBothEdgesAndSharedOwnersPreventReplay() {
        val f = Fixture(); val pulse = f.owner("pulse")
        pulse.sendCommand("X360A"); f.emitted.clear(); pulse.sendCommand("X360A")
        assertEquals(listOf(1, 0, 1), f.emitted.map { it.gamepad.buttons })
        val hold = f.owner("hold"); hold.sendCommand("X360A_HOLD")
        f.emitted.clear(); pulse.sendCommand("X360A")
        assertFalse(f.emitted.any { it.gamepad.buttons == 0 })
    }

    @Test fun localManualAndMacroShareAFirstLiveChannelAndRejectedClaimDoesNotRevive() {
        val f = Fixture(); val stick = f.owner("stick"); val macro = f.owner("macro")
        assertTrue(stick.sendStick("LS", 0f, 0f))
        assertFalse(macro.sendStickMacro("LS", 1f, 0f))
        stick.finish()
        assertFalse(macro.sendStickMacro("LS", 1f, 0f))
        assertEquals(0, f.output.state.gamepad.leftX)
        macro.releaseAllClaims()
        assertTrue(macro.sendStickMacro("LS", 1f, 0f))
        assertEquals(32767, f.output.state.gamepad.leftX)
    }

    @Test fun retainedAnalogCanBeReplacedWithoutAnOldOwnerResettingTheWinner() {
        val f = Fixture(); val old = f.owner("old"); val newer = f.owner("new")
        old.sendStick("RS", 0f, -0.5f); old.setRetained(true)
        newer.sendStickMacro("RS", 1f, 0f)
        assertEquals(32767, f.output.state.gamepad.rightX)
        old.finish(); assertEquals(32767, f.output.state.gamepad.rightX)
        newer.finish(); assertEquals(0, f.output.state.gamepad.rightX)
    }

    @Test fun replacedRetainedAnalogDoesNotReviveIfItsCallbackHasNoUiOwner() {
        val f = Fixture(); val old = f.owner("old"); val newer = f.owner("new")
        old.sendStick("LS", 0.5f, 0f); old.setRetained(true)
        assertTrue(newer.sendStickMacro("LS", -1f, 0f))
        newer.finish()
        assertFalse(old.sendStick("LS", 0.5f, 0f))
        assertEquals(Scx2Output.NEUTRAL, f.output.state.gamepad)
    }

    @Test fun firstLivePayloadReservationSurvivesAnOffGap() {
        val f = Fixture(); val repeat = f.owner("repeat"); val other = f.owner("other")
        assertTrue(repeat.reservePayload("stage", "LS:R100"))
        assertTrue(repeat.acquirePayload("stage", "LS:R100", exclusive = true))
        repeat.releasePayload("stage")
        assertFalse(other.sendStick("LS", -1f, 0f))
        repeat.endPayload("stage")
        assertFalse(other.sendStick("LS", -1f, 0f))
        other.releaseAllClaims(); assertTrue(other.sendStick("LS", -1f, 0f))
    }

    @Test fun rejectedMacroKeepsUnrelatedActionsAndCannotSpringAliveAfterWinnerLifts() {
        val f = Fixture(); val manual = f.owner("manual"); val mixed = f.owner("mixed")
        assertTrue(manual.sendStick("RS", 0f, 0f))
        assertTrue(mixed.reservePayload("run", "RS:R100 X360A RT:0.5"))
        assertTrue(mixed.acquirePayload("run", "RS:R100 X360A RT:0.5"))
        assertEquals(1, f.output.state.gamepad.buttons)
        assertEquals(32768, f.output.state.gamepad.rightTrigger)
        assertEquals(0, f.output.state.gamepad.rightX)
        manual.finish()
        mixed.releasePayload("run")
        assertTrue(mixed.acquirePayload("run", "RS:R100 X360A RT:0.5"))
        assertEquals(0, f.output.state.gamepad.rightX)
        mixed.endPayload("run")
        assertTrue(mixed.acquirePayload("fresh", "RS:R100 X360A"))
        assertEquals(32767, f.output.state.gamepad.rightX)
    }

    @Test fun androidPositiveDownIsConvertedOnceAndOpposingHatDirectionsCancel() {
        val f = Fixture(); val stick = f.owner("stick"); val up = f.owner("up"); val down = f.owner("down")
        stick.sendStick("LS", 0.5f, -1f)
        assertEquals(16384, f.output.state.gamepad.leftX); assertEquals(32767, f.output.state.gamepad.leftY)
        up.sendCommand("X360UP_HOLD"); assertEquals(0, f.output.state.gamepad.hat)
        down.sendCommand("X360DOWN_HOLD"); assertEquals(8, f.output.state.gamepad.hat)
        up.finish(); assertEquals(4, f.output.state.gamepad.hat)
        down.finish(); assertEquals(8, f.output.state.gamepad.hat)
    }

    @Test fun desktopStateIsVisibleLocallyButCannotLeakIntoTheBinaryControllerState() {
        val f = Fixture(); val control = f.owner("mixed")
        control.sendCommand("KEY_DOWN:W"); control.sendCommand("MOUSE_LEFT_DOWN")
        control.sendMouseDelta(3f, -2f); control.sendScroll(4f); control.sendCommand("X360A_HOLD")
        assertEquals(setOf("W"), f.output.state.heldKeys)
        assertEquals(setOf("LEFT"), f.output.state.heldMouseButtons)
        assertEquals(3.0, f.output.state.pointerX, 0.0); assertEquals(-2.0, f.output.state.pointerY, 0.0)
        assertEquals(4.0, f.output.state.scroll, 0.0)
        assertEquals(Scx2Output(buttons = 1), f.output.state.gamepad)
        control.finish(); assertTrue(f.output.state.heldKeys.isEmpty()); assertTrue(f.output.state.heldMouseButtons.isEmpty())
    }

    @Test fun resetInvalidatesPendingPulsesAndOldScopes() {
        val f = Fixture(); val control = f.owner("old")
        control.sendCommand("X360A"); control.sendCommand("RT:0.7")
        f.coordinator.invalidateAll(); f.output.clear()
        assertFalse(control.sendCommand("X360B_HOLD")); f.timers.forEach { it() }
        assertEquals(Scx2PracticeOutput(), f.output.state)
        assertTrue(f.owner("fresh").sendCommand("X360B_HOLD"))
        assertEquals(2, f.output.state.gamepad.buttons)
    }

    @Test fun legacyCoordinatorStillKeepsItsExistingSeparateManualMacroChannels() {
        val f = Fixture(); f.coordinator.unifiedAnalogChannels = false
        assertTrue(f.owner("manual").sendStick("LS", 0f, 0f))
        assertTrue(f.owner("macro").sendStickMacro("LS", 1f, 0f))
    }
}
