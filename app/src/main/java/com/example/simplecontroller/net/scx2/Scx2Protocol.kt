package com.example.simplecontroller.net.scx2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** SCX2's normalized, quantized complete output. Positive Y is up on the wire. */
data class Scx2Output(
    val buttons: Int = 0, val hat: Int = 8,
    val leftX: Int = 0, val leftY: Int = 0, val rightX: Int = 0, val rightY: Int = 0,
    val leftTrigger: Int = 0, val rightTrigger: Int = 0
) {
    val valid: Boolean get() = buttons in 0..1023 && hat in 0..8 &&
        listOf(leftX, leftY, rightX, rightY).all { it in -32767..32767 } &&
        leftTrigger in 0..65535 && rightTrigger in 0..65535
    companion object {
        val NEUTRAL = Scx2Output()
        fun axis(value: Double): Int {
            require(value.isFinite() && value in -1.0..1.0)
            return (sign(value) * floor(abs(value) * 32767 + 0.5)).toInt()
        }
        fun pressure(value: Double): Int {
            require(value.isFinite() && value in 0.0..1.0)
            return floor(value * 65535 + 0.5).toInt()
        }
    }
}

enum class Scx2Revision(val wire: Int) { LEGACY(2), PRESSURE(3), USB_BUTTONS(4), USB_PRESSURE(5) }
enum class Scx2Kind(val wire: Int) { HELLO(1), READY(2), STATE(3), ACK(4), STOP(5), STOPPED(6) }

/** UUID is used only as immutable 16 opaque bytes; receiver tokens are not authentication. */
data class Scx2Packet(
    val kind: Scx2Kind, val session: UUID,
    val boot: UUID = ZERO, val challenge: UUID = ZERO, val sequence: ULong = 0uL,
    val hidReady: Boolean = false, val revision: Scx2Revision = Scx2Revision.LEGACY,
    val pressureCapability: Boolean = false, val output: Scx2Output? = null,
    val usbButtonCapability: Boolean = false, val usbPressureCapability: Boolean = false
) { companion object { val ZERO = UUID(0, 0) } }

/** Exact counterpart of the checked-in Swift/C++ fixtures. No text or fallback framing. */
object Scx2Codec {
    const val PORT = 42736
    const val MAX_BYTES = 80

    fun validate(packet: Scx2Packet) {
        with(packet) {
            require(session != Scx2Packet.ZERO)
            require(kind == Scx2Kind.STATE || output == null)
            require(!pressureCapability || revision == Scx2Revision.PRESSURE)
            require(!usbButtonCapability || revision == Scx2Revision.USB_BUTTONS)
            require(!usbPressureCapability || revision == Scx2Revision.USB_PRESSURE)
            require(kind in listOf(Scx2Kind.HELLO, Scx2Kind.READY) || (!pressureCapability && !usbButtonCapability && !usbPressureCapability))
            when (kind) {
                Scx2Kind.HELLO -> require(sequence == 0uL && !hidReady &&
                    boot == Scx2Packet.ZERO && challenge == Scx2Packet.ZERO &&
                    pressureCapability == (revision == Scx2Revision.PRESSURE) &&
                    usbButtonCapability == (revision == Scx2Revision.USB_BUTTONS) &&
                    usbPressureCapability == (revision == Scx2Revision.USB_PRESSURE))
                Scx2Kind.READY -> require(sequence == 0uL && boot != Scx2Packet.ZERO &&
                    (challenge != Scx2Packet.ZERO) == hidReady &&
                    (revision != Scx2Revision.PRESSURE || pressureCapability == hidReady) &&
                    (revision != Scx2Revision.USB_BUTTONS || usbButtonCapability == hidReady) &&
                    (revision != Scx2Revision.USB_PRESSURE || usbPressureCapability == hidReady))
                else -> {
                    require(sequence > 0uL && boot != Scx2Packet.ZERO && challenge != Scx2Packet.ZERO)
                    require(hidReady == (kind == Scx2Kind.ACK))
                    if (kind == Scx2Kind.STATE || kind == Scx2Kind.ACK) require(sequence < ULong.MAX_VALUE)
                    if (kind == Scx2Kind.STATE) {
                        require(output != null && output.valid)
                        require(revision != Scx2Revision.LEGACY ||
                            (output.leftTrigger == 0 && output.rightTrigger == 0))
                        require(revision != Scx2Revision.USB_BUTTONS ||
                            (output.leftTrigger in listOf(0, 65535) && output.rightTrigger in listOf(0, 65535)))
                    }
                }
            }
        }
    }

    fun encode(packet: Scx2Packet): ByteArray {
        validate(packet)
        val length = if (packet.kind != Scx2Kind.STATE) 64 else if (packet.revision != Scx2Revision.LEGACY) 80 else 76
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buffer.put(byteArrayOf(83, 67, 88, 50))
        buffer.put(packet.revision.wire.toByte()).put(packet.kind.wire.toByte())
        buffer.put(if (packet.hidReady) 1.toByte() else 0.toByte())
        buffer.put(if (packet.pressureCapability) 1.toByte() else if (packet.usbButtonCapability) 2.toByte()
            else if (packet.usbPressureCapability) 4.toByte() else 0.toByte())
        for (id in listOf(packet.session, packet.boot, packet.challenge)) {
            buffer.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        }
        buffer.putLong(packet.sequence.toLong())
        packet.output?.let { state ->
            buffer.putShort(state.buttons.toShort()).put(state.hat.toByte()).put(0.toByte())
            for (axis in listOf(state.leftX, state.leftY, state.rightX, state.rightY)) buffer.putShort(axis.toShort())
            if (packet.revision != Scx2Revision.LEGACY) {
                buffer.putShort(state.leftTrigger.toShort()).putShort(state.rightTrigger.toShort())
            }
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Scx2Packet {
        require(bytes.size in listOf(64, 76, 80))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == 0x53435832)
        val revisionByte = buffer.get().toInt() and 255
        val revision = Scx2Revision.entries.singleOrNull { it.wire == revisionByte }
            ?: throw IllegalArgumentException("Unsupported SCX2 format")
        val kindByte = buffer.get().toInt() and 255
        val kind = Scx2Kind.entries.singleOrNull { it.wire == kindByte }
            ?: throw IllegalArgumentException("Unknown SCX2 kind")
        val flags = buffer.get().toInt() and 255
        val capability = buffer.get().toInt() and 255
        require(flags <= 1 && capability in listOf(0, 1, 2, 4))
        require(bytes.size == if (kind != Scx2Kind.STATE) 64 else if (revision != Scx2Revision.LEGACY) 80 else 76)
        fun token() = UUID(buffer.long, buffer.long)
        val session = token(); val boot = token(); val challenge = token(); val sequence = buffer.long.toULong()
        val output = if (kind == Scx2Kind.STATE) {
            val buttons = buffer.short.toInt() and 65535
            val hat = buffer.get().toInt() and 255
            require(buffer.get() == 0.toByte())
            val lx = buffer.short.toInt(); val ly = buffer.short.toInt()
            val rx = buffer.short.toInt(); val ry = buffer.short.toInt()
            val lt = if (revision != Scx2Revision.LEGACY) buffer.short.toInt() and 65535 else 0
            val rt = if (revision != Scx2Revision.LEGACY) buffer.short.toInt() and 65535 else 0
            Scx2Output(buttons, hat, lx, ly, rx, ry, lt, rt)
        } else null
        return Scx2Packet(kind, session, boot, challenge, sequence, flags == 1, revision,
            capability == 1, output, capability == 2, capability == 4).also(::validate)
    }
}
