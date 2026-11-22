package com.example.simplecontroller.net.scx2

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class UsbButtonTriggersTest {
    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val boot = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f001")
    private val challenge = UUID.fromString("11223344-5566-7788-0000-000000000001")
    private fun invalid(block: () -> Unit) { try { block(); fail("Invalid packet accepted") } catch (_: IllegalArgumentException) {} }
    private fun hello() = Scx2Packet(Scx2Kind.HELLO, id, revision = Scx2Revision.USB_BUTTONS, usbButtonCapability = true)
    private fun ready() = hello().copy(kind = Scx2Kind.READY, boot = boot, challenge = challenge, hidReady = true)
    private fun state(value: Scx2Output) = Scx2Packet(Scx2Kind.STATE, id, boot, challenge, 1uL,
        revision = Scx2Revision.USB_BUTTONS, output = value)

    @Test fun usbHelloUsesSeparateCapabilityAndExactHeader() {
        val bytes = Scx2Codec.encode(hello())
        assertEquals(64, bytes.size); assertEquals(4, bytes[4].toInt()); assertEquals(2, bytes[7].toInt())
        assertEquals(hello(), Scx2Codec.decode(bytes))
        invalid { Scx2Codec.encode(hello().copy(usbButtonCapability = false)) }
        invalid { Scx2Codec.encode(hello().copy(pressureCapability = true)) }
        for (capability in listOf(0, 1, 3, 255)) invalid { Scx2Codec.decode(bytes.copyOf().apply { this[7] = capability.toByte() }) }
    }
    @Test fun usbReadyRequiresMatchingCapabilityAndUnreadyHasNone() {
        assertEquals(ready(), Scx2Codec.decode(Scx2Codec.encode(ready())))
        invalid { Scx2Codec.encode(ready().copy(usbButtonCapability = false)) }
        val unavailable = ready().copy(challenge = Scx2Packet.ZERO, hidReady = false, usbButtonCapability = false)
        assertEquals(unavailable, Scx2Codec.decode(Scx2Codec.encode(unavailable)))
        invalid { Scx2Codec.encode(unavailable.copy(usbButtonCapability = true)) }
    }
    @Test fun usbStateOnlyAcceptsIndependentBinaryTriggerWords() {
        for (lt in listOf(0, 65535)) for (rt in listOf(0, 65535)) {
            val packet = state(Scx2Output(buttons = 1023, hat = 7, leftX = -32767, rightY = 32767, leftTrigger = lt, rightTrigger = rt))
            val bytes = Scx2Codec.encode(packet)
            assertEquals(80, bytes.size); assertEquals(0, bytes[7].toInt()); assertEquals(packet, Scx2Codec.decode(bytes))
            for (size in listOf(64, 76, 79, 81)) invalid { Scx2Codec.decode(bytes.copyOf(size)) }
        }
        for (value in listOf(1, 32768, 65534)) {
            invalid { Scx2Codec.encode(state(Scx2Output(leftTrigger = value))) }
            invalid { Scx2Codec.decode(Scx2Codec.encode(state(Scx2Output())).apply { this[76] = (value shr 8).toByte(); this[77] = value.toByte() }) }
        }
        invalid { Scx2Codec.encode(state(Scx2Output(buttons = 1024))) }
    }
    @Test fun usbNeverArmsFromLegacyOrPressureReplies() {
        val client = Scx2ClientMachine(id, Scx2Revision.USB_BUTTONS, 0)
        assertTrue(client.socketReady(0).single().usbButtonCapability)
        for (revision in listOf(Scx2Revision.LEGACY, Scx2Revision.PRESSURE)) {
            assertTrue(client.receive(ready().copy(revision = revision, usbButtonCapability = false,
                pressureCapability = revision == Scx2Revision.PRESSURE), 1).isEmpty())
            assertFalse(client.controlsEnabled)
        }
        val neutral = client.receive(ready(), 2).single()
        assertEquals(Scx2Output.NEUTRAL, neutral.output); assertFalse(client.controlsEnabled)
        client.receive(neutral.copy(kind = Scx2Kind.ACK, hidReady = true, output = null), 3)
        assertTrue(client.controlsEnabled)
        assertEquals(Scx2Kind.STOP, client.updateOutput(Scx2Output(leftTrigger = 1), 4).single().kind)
        assertEquals(Scx2ClientMachine.Failure.INVALID_STATE, client.failure)
    }
    @Test fun stopClearsBothTriggersAndDiscardsQueuedPresses() {
        val client = Scx2ClientMachine(id, Scx2Revision.USB_BUTTONS, 0)
        client.socketReady(0); val neutral = client.receive(ready(), 1).single()
        client.receive(neutral.copy(kind = Scx2Kind.ACK, hidReady = true, output = null), 2)
        client.updateOutput(Scx2Output(leftTrigger = 65535), 3)
        client.updateOutput(Scx2Output(leftTrigger = 65535, rightTrigger = 65535), 4)
        val stop = client.stop()!!
        assertEquals(Scx2Revision.USB_BUTTONS, stop.revision); assertEquals(64, Scx2Codec.encode(stop).size)
        assertEquals(Scx2Output.NEUTRAL, client.currentOutput); assertEquals(0, client.queuedCount)
    }
    @Test fun oldSettingsMigrateAndConflictsFailClosed() {
        assertEquals(BridgeTriggerMode.OFF, BridgeTriggerMode.decode(null, false))
        assertEquals(BridgeTriggerMode.ADJUSTABLE, BridgeTriggerMode.decode(null, true))
        assertEquals(BridgeTriggerMode.USB_BUTTONS, BridgeTriggerMode.decode("USB_BUTTONS", false))
        assertNull(BridgeTriggerMode.decode("USB_BUTTONS", true)); assertNull(BridgeTriggerMode.decode("ADJUSTABLE", false))
        assertNull(BridgeTriggerMode.decode("unknown", false)); assertNull(BridgeTriggerMode.decode("", false))
        assertFalse(BridgeConnectionIntent.Endpoint("127.0.0.1", 42736, true, true).valid)
    }
    @Test fun outputConversionPreservesRequestedPercentagesAndOtherControls() {
        val requested = Scx2Output(buttons = 17, leftX = 12345, rightY = -12345, leftTrigger = 32768, rightTrigger = 45875)
        assertEquals(requested, BridgeTriggerMode.ADJUSTABLE.output(requested))
        assertEquals(requested.copy(leftTrigger = 65535, rightTrigger = 65535), BridgeTriggerMode.USB_BUTTONS.output(requested))
        assertEquals(requested.copy(leftTrigger = 0, rightTrigger = 0), BridgeTriggerMode.OFF.output(requested))
        assertEquals(32768, requested.leftTrigger); assertEquals(45875, requested.rightTrigger)
    }
    @Test fun tinyPositiveRequestsStillPressInUsbModeWithoutChangingAnalogQuantization() {
        val canonical = Scx2CanonicalOutput()
        assertTrue(canonical.accept("LT:0.000001"))
        assertTrue(canonical.accept("LT:1.0E-6"))
        assertEquals(0, canonical.state.gamepad.leftTrigger)
        assertTrue(canonical.state.leftTriggerRequested)
        assertEquals(65535, BridgeTriggerMode.USB_BUTTONS.output(canonical.state.gamepad,
            canonical.state.leftTriggerRequested, canonical.state.rightTriggerRequested).leftTrigger)
        canonical.accept("LT:0.0")
        assertFalse(canonical.state.leftTriggerRequested)
        canonical.accept("RT:0.7"); canonical.clear()
        assertEquals(Scx2PracticeOutput(), canonical.state)
    }
}
