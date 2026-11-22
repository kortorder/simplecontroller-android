package com.example.simplecontroller.net.scx2

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Scx2ProtocolTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val session = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val boot = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f001")
    private val challenge = UUID.fromString("11223344-5566-7788-0000-000000000001")
    private fun invalid(block: () -> Unit) { try { block(); fail("Accepted invalid packet") } catch (_: IllegalArgumentException) {} }

    @Test fun allThirtySevenSharedSwiftCppVectorsMatchExactly() {
        var count = 0
        for (name in listOf("scx2-wire.json", "scx2-pressure-wire.json", "scx2-usb-trigger-wire.json", "scx2-usb-pressure-wire.json")) {
            val source = javaClass.getResourceAsStream("/scx2/$name")!!.bufferedReader().use { it.readText() }
            for (item in Json.parseToJsonElement(source).jsonObject.getValue("vectors").jsonArray) {
                val vector = item.jsonObject
                val bytes = hex(vector.getValue("hex").jsonPrimitive.content)
                val decoded = Scx2Codec.decode(bytes)
                assertArrayEquals(vector.getValue("name").jsonPrimitive.content, bytes, Scx2Codec.encode(decoded))
                assertEquals(vector.getValue("sequenceDecimal").jsonPrimitive.content.toULong(), decoded.sequence)
                assertEquals(vector.getValue("kind").jsonPrimitive.int, decoded.kind.wire)
                assertEquals(vector.getValue("hidReady").jsonPrimitive.boolean, decoded.hidReady)
                val state = vector["state"]
                if (state != null && state !is JsonNull) {
                    val fields = state.jsonObject; val axes = fields.getValue("axes").jsonArray.map { it.jsonPrimitive.int }
                    assertEquals(Scx2Output(fields.getValue("buttonMask").jsonPrimitive.int,
                        fields.getValue("hat").jsonPrimitive.int, axes[0], axes[1], axes[2], axes[3],
                        fields["leftPressure"]?.jsonPrimitive?.int ?: 0, fields["rightPressure"]?.jsonPrimitive?.int ?: 0), decoded.output)
                }
                count++
            }
        }
        assertEquals(37, count)
    }

    @Test fun invalidLengthHiddenFieldsAndAxisRejectWholeDatagram() {
        val valid = Scx2Codec.encode(Scx2Packet(Scx2Kind.STATE, session, boot, challenge, 1uL, output = Scx2Output()))
        for (size in listOf(0, 63, 64, 75, 77, 80, 81)) invalid { Scx2Codec.decode(valid.copyOf(size)) }
        for ((index, value) in listOf(4 to 4, 5 to 0, 6 to 2, 7 to 1, 64 to 4, 66 to 9, 67 to 1)) {
            invalid { Scx2Codec.decode(valid.copyOf().apply { this[index] = value.toByte() }) }
        }
        invalid { Scx2Codec.decode(valid.copyOf().apply { this[68] = 0x80.toByte(); this[69] = 0 }) }
    }

    @Test fun pressureCapabilityIsRequiredAndRevisionFenced() {
        invalid { Scx2Codec.encode(Scx2Packet(Scx2Kind.HELLO, session, revision = Scx2Revision.PRESSURE)) }
        invalid { Scx2Codec.encode(Scx2Packet(Scx2Kind.HELLO, session, pressureCapability = true)) }
        invalid { Scx2Codec.encode(Scx2Packet(Scx2Kind.READY, session, boot, challenge, hidReady = true, revision = Scx2Revision.PRESSURE)) }
        invalid { Scx2Codec.encode(Scx2Packet(Scx2Kind.STATE, session, boot, challenge, 1uL, output = Scx2Output(rightTrigger = 1))) }
        val pressure = Scx2Codec.encode(Scx2Packet(Scx2Kind.STATE, session, boot, challenge, 1uL,
            revision = Scx2Revision.PRESSURE, output = Scx2Output(rightTrigger = 65535)))
        invalid { Scx2Codec.decode(pressure.copyOf(76)) }
        invalid { Scx2Codec.decode(pressure.copyOf().apply { this[4] = 2 }) }
    }

    @Test fun sequenceUsesAllUnsignedBitsAndNeverAllowsMaxState() {
        val stop = Scx2Packet(Scx2Kind.STOP, session, boot, challenge, ULong.MAX_VALUE)
        assertEquals(stop, Scx2Codec.decode(Scx2Codec.encode(stop)))
        invalid { Scx2Codec.encode(stop.copy(kind = Scx2Kind.STATE, output = Scx2Output())) }
        invalid { Scx2Codec.encode(stop.copy(kind = Scx2Kind.ACK, hidReady = true)) }
        invalid { Scx2Codec.encode(stop.copy(sequence = 0uL)) }
    }

    @Test fun quantizationIsSymmetricFiniteAndPressureIsProportional() {
        assertEquals(6554, Scx2Output.pressure(0.1)); assertEquals(32768, Scx2Output.pressure(0.5))
        assertEquals(65535, Scx2Output.pressure(1.0)); assertEquals(0, Scx2Output.pressure(0.0))
        assertEquals(16384, Scx2Output.axis(0.5)); assertEquals(-16384, Scx2Output.axis(-0.5))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) invalid { Scx2Output.pressure(value) }
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.1, 1.1)) invalid { Scx2Output.axis(value) }
    }
}
