package com.example.simplecontroller.net.scx2

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connected UDP socket and one worker own one fresh session. No executor can reorder sends.
 * stop() fences input immediately; old callbacks are also fenced by the caller's route generation.
 * The destination receives only validated SCX2 datagrams, never desktop or legacy fallback text.
 */
class Scx2UdpSession(
    private val host: String, private val port: Int = Scx2Codec.PORT,
    private val revision: Scx2Revision, private val dispatch: (() -> Unit) -> Unit,
    private val onStatus: (Scx2ClientMachine.Phase, Scx2ClientMachine.Failure?) -> Unit,
    private val sessionId: UUID = UUID.randomUUID(),
    private val waitForAvailability: Boolean = false
) {
    private data class Submission(val output: Scx2Output, val completion: (Boolean) -> Unit)
    private val submissions = ArrayBlockingQueue<Submission>(128)
    private val stopped = AtomicBoolean(false)
    private val overloaded = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        require(host.isNotBlank() && port in 1..65535)
        worker = Thread(::run, "SimpleController-SCX2").apply { isDaemon = true; start() }
    }

    fun submit(output: Scx2Output, completion: (Boolean) -> Unit = {}) {
        if (stopped.get() || !started.get()) { dispatch { completion(false) }; return }
        if (!submissions.offer(Submission(output, completion))) {
            overloaded.set(true); dispatch { completion(false) }
        }
    }

    fun stop() { stopped.set(true) }

    private fun run() {
        val origin = System.nanoTime()
        fun now() = (System.nanoTime() - origin) / 1000L
        val machine = Scx2ClientMachine(sessionId, revision, now(), waitForAvailability)
        val callbacks = linkedMapOf<Long, MutableList<(Boolean) -> Unit>>()
        var socket: DatagramSocket? = null
        var reported: Pair<Scx2ClientMachine.Phase, Scx2ClientMachine.Failure?>? = null
        fun report() {
            val value = machine.phase to machine.failure
            if (value != reported) {
                reported = value
                dispatch { if (!stopped.get() || value.first == Scx2ClientMachine.Phase.STOPPED) onStatus(value.first, value.second) }
            }
        }
        fun send(packets: List<Scx2Packet>, allowStop: Boolean = false) {
            for (packet in packets) {
                if (stopped.get() && !(allowStop && packet.kind == Scx2Kind.STOP)) continue
                val bytes = Scx2Codec.encode(packet)
                socket?.send(DatagramPacket(bytes, bytes.size))
            }
        }
        fun acknowledge() {
            val finished = callbacks.keys.filter { it <= machine.lastAcknowledgedSubmission }
            finished.forEach { key -> callbacks.remove(key)?.forEach { callback -> dispatch { callback(!stopped.get()) } } }
        }
        try {
            report()
            val address = InetAddress.getByName(host)
            if (stopped.get()) return
            socket = DatagramSocket().apply { connect(address, this@Scx2UdpSession.port); soTimeout = 5 }
            send(machine.socketReady(now())); report()
            while (!stopped.get() && machine.phase != Scx2ClientMachine.Phase.STOPPED) {
                if (overloaded.get()) {
                    send(machine.queueOverflow()); report(); break
                }
                // Check the lease before processing any queued positive output.
                send(machine.tick(now())); report()
                if (!machine.controlsEnabled && machine.phase == Scx2ClientMachine.Phase.STOPPED) break
                repeat(32) {
                    val item = submissions.poll() ?: return@repeat
                    if (stopped.get() || !machine.controlsEnabled) { dispatch { item.completion(false) }; return@repeat }
                    send(machine.updateOutput(item.output, now()))
                    if (machine.controlsEnabled) {
                        if (machine.lastSubmittedId <= machine.lastAcknowledgedSubmission) dispatch { item.completion(!stopped.get()) }
                        else if (callbacks.values.sumOf { it.size } >= 128) {
                            send(machine.queueOverflow()); dispatch { item.completion(false) }
                        } else callbacks.getOrPut(machine.lastSubmittedId) { mutableListOf() }.add(item.completion)
                    } else dispatch { item.completion(false) }
                    report()
                }
                if (stopped.get() || machine.phase == Scx2ClientMachine.Phase.STOPPED) break
                // A larger buffer detects oversized packets rather than accepting an 80-byte prefix.
                val incoming = DatagramPacket(ByteArray(2048), 2048)
                try {
                    socket.receive(incoming)
                    send(machine.receive(incoming.data.copyOf(incoming.length), now())); acknowledge(); report()
                } catch (_: SocketTimeoutException) { /* tick continues at a bounded polling interval */ }
            }
        } catch (_: Exception) {
            runCatching { send(machine.transportFailed()) }; report()
        } finally {
            stopped.set(true)
            val stop = machine.stop()
            if (stop != null) repeat(3) { runCatching { send(listOf(stop), allowStop = true) } }
            socket?.close()
            callbacks.values.flatten().forEach { callback -> dispatch { callback(false) } }
            while (true) { val item = submissions.poll() ?: break; dispatch { item.completion(false) } }
        }
    }
}
