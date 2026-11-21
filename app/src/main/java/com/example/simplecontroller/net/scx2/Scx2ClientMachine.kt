package com.example.simplecontroller.net.scx2

import java.util.UUID
import java.util.ArrayDeque

/** Deterministic stop-and-wait client. All times are monotonic microseconds, never wall time. */
class Scx2ClientMachine(
    val session: UUID = UUID.randomUUID(), val revision: Scx2Revision = Scx2Revision.LEGACY,
    now: Long, private val waitForAvailability: Boolean = false
) {
    enum class Phase { CONNECTING, WAITING_FOR_NEUTRAL, READY, IDLE, STOPPED }
    enum class Failure {
        INVALID_TIME, INVALID_SESSION, INVALID_STATE, HELLO_TIMEOUT, NEUTRAL_TIMEOUT,
        ACK_TIMEOUT, USB_UNAVAILABLE, PRESSURE_UNAVAILABLE, USB_TRIGGERS_UNAVAILABLE, USB_PRESSURE_UNAVAILABLE, BRIDGE_CHANGED, QUEUE_OVERFLOW,
        SEQUENCE_EXHAUSTED, TRANSPORT
    }
    var phase = Phase.CONNECTING; private set
    var failure: Failure? = null; private set
    var issuedSequence = 0uL; private set
    var lastAcknowledgedSequence = 0uL; private set
    var lastSubmittedId = 0L; private set
    var lastAcknowledgedSubmission = 0L; private set
    var currentOutput = Scx2Output.NEUTRAL; private set
    val controlsEnabled get() = phase == Phase.READY
    val queuedCount get() = queue.size

    private var boot = Scx2Packet.ZERO
    private var challenge = Scx2Packet.ZERO
    private data class Entry(val output: Scx2Output, val submission: Long)
    private data class Outstanding(val packet: Scx2Packet, val submission: Long, var lastSent: Long)
    private val queue = ArrayDeque<Entry>()
    private var outstanding: Outstanding? = null
    private var socketReady = false
    private val started = now
    private var lastTime = now
    private var lastProgress = now
    private var lastHello: Long? = null
    private var lastIssued: Long? = null

    init {
        if (!validTime(now)) { phase = Phase.STOPPED; failure = Failure.INVALID_TIME }
        else if (session == Scx2Packet.ZERO) { phase = Phase.STOPPED; failure = Failure.INVALID_SESSION }
    }

    fun socketReady(now: Long): List<Scx2Packet> { socketReady = true; return tick(now) }

    /** Expiry runs even for corrupt/stale incoming packets. */
    fun receive(bytes: ByteArray, now: Long): List<Scx2Packet> {
        advance(now)?.let { return it }
        val packet = runCatching { Scx2Codec.decode(bytes) }.getOrNull() ?: return emptyList()
        return receiveValid(packet, now)
    }

    fun receive(packet: Scx2Packet, now: Long): List<Scx2Packet> {
        advance(now)?.let { return it }
        if (runCatching { Scx2Codec.validate(packet) }.isFailure) return emptyList()
        return receiveValid(packet, now)
    }

    private fun receiveValid(packet: Scx2Packet, now: Long): List<Scx2Packet> {
        if (packet.session != session || packet.revision != revision) return emptyList()
        if (packet.kind == Scx2Kind.READY) {
            if (phase !in listOf(Phase.CONNECTING, Phase.WAITING_FOR_NEUTRAL, Phase.READY)) return emptyList()
            if (!packet.hidReady) {
                // A retiring receiver session must finish USB neutral before accepting a new one.
                // Only automatic recovery waits, and no reply can extend the original deadline.
                if (phase == Phase.CONNECTING && waitForAvailability) return emptyList()
                return fail(when (revision) {
                    Scx2Revision.PRESSURE -> Failure.PRESSURE_UNAVAILABLE
                    Scx2Revision.USB_BUTTONS -> Failure.USB_TRIGGERS_UNAVAILABLE
                    Scx2Revision.USB_PRESSURE -> Failure.USB_PRESSURE_UNAVAILABLE
                    else -> Failure.USB_UNAVAILABLE
                })
            }
            if (phase == Phase.CONNECTING) {
                boot = packet.boot; challenge = packet.challenge; lastProgress = now
                phase = Phase.WAITING_FOR_NEUTRAL
                return issue(Entry(Scx2Output.NEUTRAL, 0), now)
            }
            if (packet.boot != boot || packet.challenge != challenge) return fail(Failure.BRIDGE_CHANGED)
            return emptyList() // Duplicate READY does not renew any deadline.
        }
        val pending = outstanding ?: return emptyList()
        if (phase !in listOf(Phase.WAITING_FOR_NEUTRAL, Phase.READY) || packet.kind != Scx2Kind.ACK ||
            packet.boot != boot || packet.challenge != challenge || packet.sequence != pending.packet.sequence) return emptyList()
        outstanding = null; lastProgress = now; lastAcknowledgedSequence = packet.sequence
        lastAcknowledgedSubmission = pending.submission
        if (phase == Phase.WAITING_FOR_NEUTRAL) phase = Phase.READY
        return if (queue.isEmpty()) emptyList() else issue(queue.removeFirst(), now)
    }

    /** New full snapshots are retained in order; only identical adjacent snapshots deduplicate. */
    fun updateOutput(output: Scx2Output, now: Long): List<Scx2Packet> {
        advance(now)?.let { return it }
        if (phase != Phase.READY) return emptyList()
        if (!output.valid || (revision == Scx2Revision.LEGACY &&
                (output.leftTrigger != 0 || output.rightTrigger != 0)) ||
            (revision == Scx2Revision.USB_BUTTONS && (output.leftTrigger !in listOf(0, 65535) || output.rightTrigger !in listOf(0, 65535)))) return fail(Failure.INVALID_STATE)
        if (output == currentOutput) return emptyList()
        if (lastSubmittedId == Long.MAX_VALUE) return fail(Failure.SEQUENCE_EXHAUSTED)
        currentOutput = output; lastSubmittedId++
        val entry = Entry(output, lastSubmittedId)
        if (outstanding == null) return issue(entry, now)
        if (queue.size == QUEUE_CAPACITY) return fail(Failure.QUEUE_OVERFLOW)
        queue.add(entry)
        return emptyList()
    }

    fun tick(now: Long): List<Scx2Packet> {
        advance(now)?.let { return it }
        when (phase) {
            Phase.CONNECTING -> if (socketReady && (lastHello == null || now - lastHello!! >= HELLO_INTERVAL)) {
                lastHello = now
                return listOf(Scx2Packet(Scx2Kind.HELLO, session, revision = revision,
                    pressureCapability = revision == Scx2Revision.PRESSURE,
                    usbButtonCapability = revision == Scx2Revision.USB_BUTTONS,
                    usbPressureCapability = revision == Scx2Revision.USB_PRESSURE))
            }
            Phase.WAITING_FOR_NEUTRAL, Phase.READY -> {
                outstanding?.let {
                    if (now - it.lastSent < RETRY_INTERVAL) return emptyList()
                    it.lastSent = now
                    return listOf(it.packet)
                }
                if (phase == Phase.READY && (lastIssued == null || now - lastIssued!! >= RETRY_INTERVAL))
                    return issue(Entry(currentOutput, lastSubmittedId), now)
            }
            else -> Unit
        }
        return emptyList()
    }

    /** Stop skips every queued press. Callers may retry this same STOP, never queued STATE. */
    fun stop(): Scx2Packet? {
        val packet = if (boot != Scx2Packet.ZERO && challenge != Scx2Packet.ZERO && issuedSequence < ULong.MAX_VALUE)
            Scx2Packet(Scx2Kind.STOP, session, boot, challenge, issuedSequence + 1uL, revision = revision) else null
        phase = Phase.IDLE; boot = Scx2Packet.ZERO; challenge = Scx2Packet.ZERO
        queue.clear(); outstanding = null; currentOutput = Scx2Output.NEUTRAL; socketReady = false
        return packet
    }

    fun transportFailed(): List<Scx2Packet> = if (phase == Phase.IDLE || phase == Phase.STOPPED) emptyList() else fail(Failure.TRANSPORT)
    fun queueOverflow(): List<Scx2Packet> = if (phase == Phase.IDLE || phase == Phase.STOPPED) emptyList() else fail(Failure.QUEUE_OVERFLOW)

    private fun issue(entry: Entry, now: Long): List<Scx2Packet> {
        if (issuedSequence >= ULong.MAX_VALUE - 1uL) return fail(Failure.SEQUENCE_EXHAUSTED)
        issuedSequence++; lastIssued = now
        val packet = Scx2Packet(Scx2Kind.STATE, session, boot, challenge, issuedSequence,
            revision = revision, output = entry.output)
        outstanding = Outstanding(packet, entry.submission, now)
        return listOf(packet)
    }

    private fun fail(reason: Failure): List<Scx2Packet> {
        val packet = stop(); phase = Phase.STOPPED; failure = reason
        return listOfNotNull(packet)
    }

    private fun advance(now: Long): List<Scx2Packet>? {
        if (phase == Phase.IDLE || phase == Phase.STOPPED) return emptyList()
        if (!validTime(now) || now < lastTime) return fail(Failure.INVALID_TIME)
        lastTime = now
        if (phase == Phase.CONNECTING && now - started >= HELLO_TIMEOUT) return fail(Failure.HELLO_TIMEOUT)
        if (phase in listOf(Phase.WAITING_FOR_NEUTRAL, Phase.READY) && now - lastProgress >= ACK_TIMEOUT)
            return fail(if (phase == Phase.WAITING_FOR_NEUTRAL) Failure.NEUTRAL_TIMEOUT else Failure.ACK_TIMEOUT)
        return null
    }

    companion object {
        const val QUEUE_CAPACITY = 16
        const val RETRY_INTERVAL = 100_000L
        const val HELLO_INTERVAL = 250_000L
        const val ACK_TIMEOUT = 750_000L
        const val HELLO_TIMEOUT = 3_000_000L
        private fun validTime(value: Long) = value >= 0 && value <= Long.MAX_VALUE - HELLO_TIMEOUT
    }
}
