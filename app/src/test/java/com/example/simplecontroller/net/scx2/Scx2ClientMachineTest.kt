package com.example.simplecontroller.net.scx2

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class Scx2ClientMachineTest {
    private class Fixture(val revision: Scx2Revision = Scx2Revision.LEGACY) {
        val machine = Scx2ClientMachine(revision = revision, now = 0)
        val ready = Scx2Packet(Scx2Kind.READY, machine.session, UUID.randomUUID(), UUID.randomUUID(),
            hidReady = true, revision = revision, pressureCapability = revision == Scx2Revision.PRESSURE, usbButtonCapability = revision == Scx2Revision.USB_BUTTONS, usbPressureCapability = revision == Scx2Revision.USB_PRESSURE)
        fun ack(sequence: ULong) = ready.copy(kind = Scx2Kind.ACK, sequence = sequence, pressureCapability = false, usbButtonCapability = false)
        fun arm() {
            assertEquals(Scx2Kind.HELLO, machine.socketReady(0).single().kind)
            val neutral = machine.receive(ready, 10).single()
            assertEquals(Scx2Output.NEUTRAL, neutral.output); assertFalse(machine.controlsEnabled)
            machine.receive(ack(1uL), 20); assertTrue(machine.controlsEnabled)
        }
    }

    @Test fun neutralAckGatesAllInputAndWrongTokensCannotArm() {
        val f = Fixture(Scx2Revision.PRESSURE)
        assertTrue(f.machine.updateOutput(Scx2Output(buttons = 1), 0).isEmpty())
        f.machine.socketReady(0)
        f.machine.receive(f.ready, 1)
        for (wrong in listOf(f.ack(2uL), f.ack(1uL).copy(session = UUID.randomUUID()),
            f.ack(1uL).copy(boot = UUID.randomUUID()), f.ack(1uL).copy(challenge = UUID.randomUUID()),
            f.ack(1uL).copy(revision = Scx2Revision.LEGACY))) {
            assertTrue(f.machine.receive(wrong, 2).isEmpty()); assertFalse(f.machine.controlsEnabled)
        }
        f.machine.receive(f.ack(1uL), 3); assertTrue(f.machine.controlsEnabled)
        assertEquals(Scx2Output.NEUTRAL, f.machine.currentOutput)
    }

    @Test fun pressReleaseAndFractionalChangesRemainOrderedUntilTheirOwnAck() {
        val f = Fixture(Scx2Revision.PRESSURE); f.arm()
        val first = Scx2Output(buttons = 1, rightTrigger = Scx2Output.pressure(0.2))
        assertEquals(first, f.machine.updateOutput(first, 30).single().output)
        val second = first.copy(rightTrigger = Scx2Output.pressure(0.8))
        assertTrue(f.machine.updateOutput(second, 31).isEmpty())
        assertTrue(f.machine.updateOutput(Scx2Output.NEUTRAL, 32).isEmpty())
        assertEquals(2, f.machine.queuedCount)
        assertEquals(second, f.machine.receive(f.ack(2uL), 40).single().output)
        assertEquals(1, f.machine.lastAcknowledgedSubmission)
        assertEquals(Scx2Output.NEUTRAL, f.machine.receive(f.ack(3uL), 41).single().output)
        f.machine.receive(f.ack(4uL), 42)
        assertEquals(3, f.machine.lastAcknowledgedSubmission)
    }

    @Test fun retryKeepsIdentityAndDuplicateReadyCannotExtendLease() {
        val f = Fixture(); f.arm()
        val state = f.machine.updateOutput(Scx2Output(buttons = 1), 30).single()
        assertEquals(state, f.machine.tick(100_030).single())
        f.machine.receive(f.ready, 700_000)
        assertEquals(Scx2Kind.STOP, f.machine.tick(750_020).single().kind)
        assertEquals(Scx2ClientMachine.Failure.ACK_TIMEOUT, f.machine.failure)
        assertFalse(f.machine.controlsEnabled)
    }

    @Test fun corruptOrMatchingAckAtDeadlineCannotResurrect() {
        for (corrupt in listOf(false, true)) {
            val f = Fixture(); f.arm(); f.machine.updateOutput(Scx2Output(buttons = 1), 30)
            val result = if (corrupt) f.machine.receive(byteArrayOf(0), 750_020) else f.machine.receive(f.ack(2uL), 750_020)
            assertEquals(Scx2Kind.STOP, result.single().kind)
            assertEquals(Scx2Output.NEUTRAL, f.machine.currentOutput)
            assertTrue(f.machine.receive(f.ack(2uL), 750_021).isEmpty())
        }
    }

    @Test fun overflowDiscardsPendingEdgesInsteadOfDroppingOne() {
        val f = Fixture(); f.arm()
        f.machine.updateOutput(Scx2Output(buttons = 1), 30)
        repeat(16) { f.machine.updateOutput(Scx2Output(buttons = if (it % 2 == 0) 0 else 1), 31L + it) }
        assertEquals(16, f.machine.queuedCount)
        assertEquals(Scx2Kind.STOP, f.machine.updateOutput(Scx2Output(buttons = 2), 50).single().kind)
        assertEquals(Scx2ClientMachine.Failure.QUEUE_OVERFLOW, f.machine.failure)
        assertEquals(0, f.machine.queuedCount)
        assertEquals(Scx2Output.NEUTRAL, f.machine.currentOutput)
    }

    @Test fun explicitStopSkipsQueueAndNewMachineNeedsFreshNeutral() {
        val f = Fixture(); f.arm(); f.machine.updateOutput(Scx2Output(buttons = 1), 30)
        f.machine.updateOutput(Scx2Output(buttons = 2), 31)
        val stop = f.machine.stop()!!
        assertEquals(3uL, stop.sequence); assertEquals(Scx2Kind.STOP, stop.kind)
        assertTrue(f.machine.tick(40).isEmpty()); assertTrue(f.machine.receive(f.ack(2uL), 41).isEmpty())
        val next = Fixture(); assertNotEquals(f.machine.session, next.machine.session)
        assertTrue(next.machine.receive(f.ready, 0).isEmpty()); assertFalse(next.machine.controlsEnabled)
        next.arm()
    }

    @Test fun unavailablePressureNeverDowngradesAndChangedBootStops() {
        val f = Fixture(Scx2Revision.PRESSURE)
        f.machine.receive(f.ready.copy(hidReady = false, pressureCapability = false, challenge = Scx2Packet.ZERO), 1)
        assertEquals(Scx2ClientMachine.Failure.PRESSURE_UNAVAILABLE, f.machine.failure)
        val old = Fixture(Scx2Revision.PRESSURE)
        assertTrue(old.machine.receive(old.ready.copy(revision = Scx2Revision.LEGACY, pressureCapability = false, usbButtonCapability = false), 1).isEmpty())
        old.machine.tick(3_000_000); assertEquals(Scx2ClientMachine.Failure.HELLO_TIMEOUT, old.machine.failure)
        val changed = Fixture(); changed.arm()
        assertEquals(Scx2Kind.STOP, changed.machine.receive(changed.ready.copy(boot = UUID.randomUUID()), 30).single().kind)
        assertEquals(Scx2ClientMachine.Failure.BRIDGE_CHANGED, changed.machine.failure)
    }

    @Test fun heartbeatNeedsNewAcknowledgementAndCannotSilentlyCarryPressureInV2() {
        val f = Fixture(); f.arm()
        assertEquals(2uL, f.machine.tick(100_010).single().sequence)
        f.machine.receive(f.ack(2uL), 100_020)
        assertEquals(Scx2ClientMachine.Phase.READY, f.machine.phase)
        assertEquals(Scx2Kind.STOP, f.machine.updateOutput(Scx2Output(rightTrigger = 1), 100_030).single().kind)
        assertEquals(Scx2ClientMachine.Failure.INVALID_STATE, f.machine.failure)
    }

    @Test fun backwardsAndUnrepresentableClockFailNeutral() {
        val f = Fixture(Scx2Revision.PRESSURE); f.arm()
        f.machine.updateOutput(Scx2Output(rightTrigger = 65535), 30)
        f.machine.tick(29); assertEquals(Scx2ClientMachine.Failure.INVALID_TIME, f.machine.failure)
        assertEquals(Scx2Output.NEUTRAL, f.machine.currentOutput)
        assertEquals(Scx2ClientMachine.Failure.INVALID_TIME, Scx2ClientMachine(now = Long.MAX_VALUE).failure)
        assertEquals(Scx2ClientMachine.Failure.INVALID_SESSION, Scx2ClientMachine(Scx2Packet.ZERO, now = 0).failure)
    }
    @Test fun automaticAvailabilityWaitKeepsOriginalDeadlineAndNeutralGate() {
        for (revision in Scx2Revision.values()) {
            val machine = Scx2ClientMachine(revision = revision, now = 0, waitForAvailability = true)
            val unavailable = Scx2Packet(Scx2Kind.READY, machine.session, UUID.randomUUID(),
                revision = revision, hidReady = false)
            machine.socketReady(0)
            assertTrue(machine.receive(unavailable, 100).isEmpty())
            assertEquals(Scx2ClientMachine.Phase.CONNECTING, machine.phase)
            assertFalse(machine.controlsEnabled)
            assertTrue(machine.updateOutput(Scx2Output(buttons = 1), 101).isEmpty())
            assertEquals(Scx2Kind.HELLO, machine.tick(250_000).single().kind)
            machine.receive(unavailable, 2_999_999)
            machine.tick(3_000_000)
            assertEquals(Scx2ClientMachine.Failure.HELLO_TIMEOUT, machine.failure)
            assertTrue(machine.receive(unavailable.copy(hidReady = true, challenge = UUID.randomUUID(),
                pressureCapability = revision == Scx2Revision.PRESSURE, usbButtonCapability = revision == Scx2Revision.USB_BUTTONS, usbPressureCapability = revision == Scx2Revision.USB_PRESSURE), 3_000_001).isEmpty())
            assertFalse(machine.controlsEnabled)
        }
    }

    @Test fun automaticWaitRequiresFreshMatchingNeutralAcknowledgement() {
        for (revision in Scx2Revision.values()) {
            val machine = Scx2ClientMachine(revision = revision, now = 0, waitForAvailability = true)
            val ready = Scx2Packet(Scx2Kind.READY, machine.session, UUID.randomUUID(), UUID.randomUUID(),
                hidReady = true, revision = revision, pressureCapability = revision == Scx2Revision.PRESSURE, usbButtonCapability = revision == Scx2Revision.USB_BUTTONS, usbPressureCapability = revision == Scx2Revision.USB_PRESSURE)
            machine.socketReady(0)
            machine.receive(ready.copy(hidReady = false, challenge = Scx2Packet.ZERO, pressureCapability = false, usbButtonCapability = false), 10)
            val neutral = machine.receive(ready, 250_000).single()
            assertEquals(Scx2Output.NEUTRAL, neutral.output); assertEquals(1uL, neutral.sequence)
            assertFalse(machine.controlsEnabled)
            val ack = neutral.copy(kind = Scx2Kind.ACK, hidReady = true, output = null)
            machine.receive(ack.copy(session = UUID.randomUUID()), 250_001)
            assertFalse(machine.controlsEnabled)
            machine.receive(ack, 250_002)
            assertTrue(machine.controlsEnabled); assertEquals(Scx2Output.NEUTRAL, machine.currentOutput)
        }
    }

    @Test fun unavailableReadyAfterArmingStillStopsEvenForAutomaticAttempt() {
        val machine = Scx2ClientMachine(now = 0, waitForAvailability = true)
        val ready = Scx2Packet(Scx2Kind.READY, machine.session, UUID.randomUUID(), UUID.randomUUID(), hidReady = true)
        machine.socketReady(0)
        val neutral = machine.receive(ready, 1).single()
        machine.receive(neutral.copy(kind = Scx2Kind.ACK, hidReady = true, output = null), 2)
        machine.receive(ready.copy(hidReady = false, challenge = Scx2Packet.ZERO), 3)
        assertEquals(Scx2ClientMachine.Failure.USB_UNAVAILABLE, machine.failure)
        assertFalse(machine.controlsEnabled)
    }

}
