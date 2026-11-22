package com.example.simplecontroller.net.scx2

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CronusZenTriggersTest {
    private val session = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val boot = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f001")
    private val challenge = UUID.fromString("11223344-5566-7788-0000-000000000001")
    private fun hello() = Scx2Packet(Scx2Kind.HELLO, session, revision = Scx2Revision.USB_PRESSURE,
        usbPressureCapability = true)
    private fun ready() = hello().copy(kind = Scx2Kind.READY, boot = boot, challenge = challenge, hidReady = true)
    private fun invalid(action: () -> Unit) { try { action(); fail("Invalid packet accepted") } catch (_: IllegalArgumentException) {} }
    private fun arm(): Scx2ClientMachine {
        val machine = Scx2ClientMachine(session, Scx2Revision.USB_PRESSURE, 0)
        assertEquals(hello(), machine.socketReady(0).single())
        val neutral = machine.receive(ready(), 1).single()
        assertEquals(Scx2Output.NEUTRAL, neutral.output)
        assertFalse(machine.controlsEnabled)
        machine.receive(neutral.copy(kind = Scx2Kind.ACK, hidReady = true, output = null), 2)
        assertTrue(machine.controlsEnabled)
        return machine
    }

    @Test fun independentUsbPressureCapabilityIsRequiredExactly() {
        val bytes = Scx2Codec.encode(hello())
        assertEquals(64, bytes.size); assertEquals(5, bytes[4].toInt()); assertEquals(4, bytes[7].toInt())
        assertEquals(hello(), Scx2Codec.decode(bytes))
        for (capability in listOf(0, 1, 2, 3, 5, 6, 7, 255)) invalid {
            Scx2Codec.decode(bytes.copyOf().apply { this[7] = capability.toByte() })
        }
        invalid { Scx2Codec.encode(hello().copy(pressureCapability = true)) }
        invalid { Scx2Codec.encode(hello().copy(usbButtonCapability = true)) }
        for (revision in Scx2Revision.entries.filter { it != Scx2Revision.USB_PRESSURE }) invalid {
            Scx2Codec.encode(hello().copy(revision = revision))
        }
    }

    @Test fun unreadyReplyCannotAdvertiseWorkingUsbPressure() {
        assertEquals(ready(), Scx2Codec.decode(Scx2Codec.encode(ready())))
        invalid { Scx2Codec.encode(ready().copy(usbPressureCapability = false)) }
        val unavailable = ready().copy(hidReady = false, challenge = Scx2Packet.ZERO, usbPressureCapability = false)
        assertEquals(unavailable, Scx2Codec.decode(Scx2Codec.encode(unavailable)))
        invalid { Scx2Codec.encode(unavailable.copy(usbPressureCapability = true)) }
        val machine = Scx2ClientMachine(session, Scx2Revision.USB_PRESSURE, 0)
        machine.socketReady(0); machine.receive(unavailable, 1)
        assertEquals(Scx2ClientMachine.Failure.USB_PRESSURE_UNAVAILABLE, machine.failure)
        assertFalse(machine.controlsEnabled)
    }

    @Test fun fractionalValuesAndEveryExistingControlSurviveWireRoundTrip() {
        for (lt in listOf(0, 1, 6554, 32768, 45875, 65534, 65535)) for (rt in listOf(0, 1, 45875, 65535)) {
            val requested = Scx2Output(1023, 7, -32767, 32767, 16384, -16384, lt, rt)
            val packet = Scx2Packet(Scx2Kind.STATE, session, boot, challenge, 1uL,
                revision = Scx2Revision.USB_PRESSURE, output = requested)
            val bytes = Scx2Codec.encode(packet)
            assertEquals(80, bytes.size); assertEquals(0, bytes[7].toInt())
            assertEquals(packet, Scx2Codec.decode(bytes))
            assertEquals(requested, BridgeTriggerMode.CRONUS_ZEN.output(requested))
            for (size in listOf(64, 76, 79, 81)) invalid { Scx2Codec.decode(bytes.copyOf(size)) }
        }
    }

    @Test fun noFallbackToCircuitOrBinaryOrLegacyReceiver() {
        val machine = Scx2ClientMachine(session, Scx2Revision.USB_PRESSURE, 0)
        machine.socketReady(0)
        for (revision in Scx2Revision.entries.filter { it != Scx2Revision.USB_PRESSURE }) {
            machine.receive(ready().copy(revision = revision, usbPressureCapability = false,
                pressureCapability = revision == Scx2Revision.PRESSURE,
                usbButtonCapability = revision == Scx2Revision.USB_BUTTONS), 1)
            assertFalse(machine.controlsEnabled)
            assertEquals(Scx2ClientMachine.Phase.CONNECTING, machine.phase)
        }
        machine.tick(3_000_001)
        assertEquals(Scx2ClientMachine.Failure.HELLO_TIMEOUT, machine.failure)
    }

    @Test fun neutralBarrierThenPartialPressureAndReleaseKeepSequenceOrdering() {
        val machine = arm()
        val press = machine.updateOutput(Scx2Output(leftTrigger = 32768, rightTrigger = 45875), 3).single()
        assertEquals(32768, press.output!!.leftTrigger)
        assertTrue(machine.updateOutput(Scx2Output.NEUTRAL, 4).isEmpty())
        val release = machine.receive(press.copy(kind = Scx2Kind.ACK, output = null, hidReady = true), 5).single()
        assertEquals(Scx2Output.NEUTRAL, release.output)
        assertEquals(press.sequence + 1uL, release.sequence)
        assertEquals(Scx2Revision.USB_PRESSURE, release.revision)
    }

    @Test fun stopAndTimeoutDiscardAllQueuedActions() {
        for (explicit in listOf(false, true)) {
            val machine = arm()
            machine.updateOutput(Scx2Output(leftTrigger = 32768), 3)
            machine.updateOutput(Scx2Output(rightTrigger = 45875, buttons = 1), 4)
            val stop = if (explicit) machine.stop()!! else machine.tick(1_000_003).single()
            assertEquals(Scx2Kind.STOP, stop.kind)
            assertEquals(Scx2Revision.USB_PRESSURE, stop.revision)
            assertEquals(0, machine.queuedCount)
            assertEquals(Scx2Output.NEUTRAL, machine.currentOutput)
            assertFalse(machine.controlsEnabled)
        }
    }

    @Test fun settingsPreserveOldRoutesAndRejectContradictoryPressureRecords() {
        assertEquals(BridgeTriggerMode.CRONUS_ZEN, BridgeTriggerMode.decode("CRONUS_ZEN", false))
        assertNull(BridgeTriggerMode.decode("CRONUS_ZEN", true))
        assertEquals(BridgeTriggerMode.OFF, BridgeTriggerMode.decode(null, false))
        assertEquals(BridgeTriggerMode.ADJUSTABLE, BridgeTriggerMode.decode(null, true))
        for (mode in BridgeTriggerMode.entries) {
            val endpoint = BridgeConnectionIntent.Endpoint("127.0.0.1", 42736, mode)
            assertTrue(endpoint.valid); assertEquals(mode, endpoint.triggerMode)
        }
        assertFalse(BridgeConnectionIntent.Endpoint("127.0.0.1", 42736, true, false, true).valid)
        assertFalse(BridgeConnectionIntent.Endpoint("127.0.0.1", 42736, false, true, true).valid)
    }

    @Test fun fiveMinuteRestoreRetainsZenRouteWithoutAnyHeldInput() {
        val endpoint = BridgeConnectionIntent.Endpoint("127.0.0.1", 42736, BridgeTriggerMode.CRONUS_ZEN)
        val intent = BridgeConnectionIntent()
        assertTrue(intent.connect(endpoint, 1000)); intent.background(1001)
        val saved = intent.snapshot()!!
        val restored = BridgeConnectionIntent()
        assertTrue(restored.restore(saved, 2000)); assertFalse(restored.mayConnect)
        assertTrue(restored.foreground(2000)); assertEquals(endpoint, restored.endpoint)
        val expired = BridgeConnectionIntent()
        assertFalse(expired.restore(saved, 301001)); assertNull(expired.endpoint)
    }
}
