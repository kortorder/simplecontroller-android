package com.example.simplecontroller.net.scx2

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real 127.0.0.1 UDP with a scripted protocol peer; it makes no USB/XAC hardware claim. */
class Scx2UdpSessionTest {
    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1500 }
        var destination: DatagramPacket? = null
        val boot = UUID.randomUUID(); val challenge = UUID.randomUUID()
        fun receive(kind: Scx2Kind): Scx2Packet {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < limit) {
                val datagram = DatagramPacket(ByteArray(2048), 2048)
                socket.receive(datagram); destination = datagram
                val packet = Scx2Codec.decode(datagram.data.copyOf(datagram.length))
                if (packet.kind == kind) return packet
            }
            throw AssertionError("No $kind received")
        }
        fun receiveAfterState(previous: Scx2Packet): Scx2Packet {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < limit) {
                val packet = receive(Scx2Kind.STATE)
                if (packet.sequence != previous.sequence) return packet
                // A retransmission can already be in flight when its ACK is sent.
                // It must be byte-for-byte equivalent, never a new action at an old sequence.
                assertEquals(previous, packet)
                send(ack(packet))
            }
            throw AssertionError("No state after ${previous.sequence}")
        }
        fun send(packet: Scx2Packet, from: DatagramSocket = socket) {
            val bytes = Scx2Codec.encode(packet); val target = destination!!
            from.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
        }
        fun ready(hello: Scx2Packet) = hello.copy(kind = Scx2Kind.READY, boot = boot,
            challenge = challenge, hidReady = true)
        fun ack(state: Scx2Packet) = state.copy(kind = Scx2Kind.ACK, hidReady = true, output = null)
        override fun close() { socket.close() }
    }

    private fun session(peer: Peer, revision: Scx2Revision,
                        statuses: LinkedBlockingQueue<Scx2ClientMachine.Phase>) =
        Scx2UdpSession("127.0.0.1", peer.socket.localPort, revision, { it() },
            { phase, _ -> statuses.offer(phase) })

    private fun awaitReady(statuses: LinkedBlockingQueue<Scx2ClientMachine.Phase>) {
        repeat(4) { if (statuses.poll(1, TimeUnit.SECONDS) == Scx2ClientMachine.Phase.READY) return }
        fail("No neutral-acknowledged ready callback")
    }

    @Test fun actualSocketRequiresNeutralAckAndDeliversFractionalPressReleaseInOrder() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val client = session(peer, Scx2Revision.PRESSURE, statuses)
            try {
                client.start()
                val hello = peer.receive(Scx2Kind.HELLO)
                assertEquals(Scx2Revision.PRESSURE, hello.revision); assertTrue(hello.pressureCapability)
                peer.send(peer.ready(hello))
                val neutral = peer.receive(Scx2Kind.STATE)
                assertEquals(1uL, neutral.sequence); assertEquals(Scx2Output.NEUTRAL, neutral.output)
                val rejected = LinkedBlockingQueue<Boolean>()
                client.submit(Scx2Output(buttons = 1)) { rejected.offer(it) }
                assertEquals(false, rejected.poll(300, TimeUnit.MILLISECONDS))
                peer.send(peer.ack(neutral)); awaitReady(statuses)
                val completions = LinkedBlockingQueue<Boolean>()
                val first = Scx2Output(buttons = 1, rightTrigger = 13107)
                val second = first.copy(rightTrigger = 52428)
                for (output in listOf(first, second, Scx2Output.NEUTRAL)) client.submit(output) { completions.offer(it) }
                val down = peer.receiveAfterState(neutral)
                assertEquals(first, down.output)
                val retry = peer.receive(Scx2Kind.STATE)
                assertEquals(down, retry); assertNull(completions.poll())
                peer.send(peer.ack(down))
                val more = peer.receiveAfterState(down)
                assertEquals(second, more.output); assertEquals(down.sequence + 1uL, more.sequence)
                peer.send(peer.ack(more))
                val up = peer.receiveAfterState(more)
                assertEquals(Scx2Output.NEUTRAL, up.output); assertEquals(more.sequence + 1uL, up.sequence)
                peer.send(peer.ack(up))
                repeat(3) { assertEquals(true, completions.poll(500, TimeUnit.MILLISECONDS)) }
                client.stop(); assertEquals(Scx2Kind.STOP, peer.receive(Scx2Kind.STOP).kind)
            } finally { client.stop() }
        }
    }

    @Test fun cronusZenSocketPreservesPartialPressuresAndReleaseOrder() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val client = session(peer, Scx2Revision.USB_PRESSURE, statuses)
            try {
                client.start()
                val hello = peer.receive(Scx2Kind.HELLO)
                assertEquals(Scx2Revision.USB_PRESSURE, hello.revision); assertTrue(hello.usbPressureCapability)
                peer.send(peer.ready(hello))
                val neutral = peer.receive(Scx2Kind.STATE)
                assertEquals(1uL, neutral.sequence); assertEquals(Scx2Output.NEUTRAL, neutral.output)
                val rejected = LinkedBlockingQueue<Boolean>()
                client.submit(Scx2Output(buttons = 1)) { rejected.offer(it) }
                assertEquals(false, rejected.poll(300, TimeUnit.MILLISECONDS))
                peer.send(peer.ack(neutral)); awaitReady(statuses)
                val completions = LinkedBlockingQueue<Boolean>()
                val first = Scx2Output(buttons = 1, rightTrigger = 13107)
                val second = first.copy(rightTrigger = 52428)
                for (output in listOf(first, second, Scx2Output.NEUTRAL)) client.submit(output) { completions.offer(it) }
                val down = peer.receiveAfterState(neutral)
                assertEquals(first, down.output)
                val retry = peer.receive(Scx2Kind.STATE)
                assertEquals(down, retry); assertNull(completions.poll())
                peer.send(peer.ack(down))
                val more = peer.receiveAfterState(down)
                assertEquals(second, more.output); assertEquals(down.sequence + 1uL, more.sequence)
                peer.send(peer.ack(more))
                val up = peer.receiveAfterState(more)
                assertEquals(Scx2Output.NEUTRAL, up.output); assertEquals(more.sequence + 1uL, up.sequence)
                peer.send(peer.ack(up))
                repeat(3) { assertEquals(true, completions.poll(500, TimeUnit.MILLISECONDS)) }
                client.stop(); assertEquals(Scx2Kind.STOP, peer.receive(Scx2Kind.STOP).kind)
            } finally { client.stop() }
        }
    }

    @Test fun connectedSocketRejectsValidAckFromOtherEndpoint() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val client = session(peer, Scx2Revision.LEGACY, statuses)
            try {
                client.start(); val hello = peer.receive(Scx2Kind.HELLO); peer.send(peer.ready(hello))
                val neutral = peer.receive(Scx2Kind.STATE)
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { stranger -> peer.send(peer.ack(neutral), stranger) }
                val retry = peer.receive(Scx2Kind.STATE)
                assertEquals(neutral, retry)
                assertFalse(statuses.toList().contains(Scx2ClientMachine.Phase.READY))
                peer.send(peer.ack(neutral)); awaitReady(statuses)
            } finally { client.stop() }
        }
    }

    @Test fun stopDropsQueuedPositiveStatesAndFailsTheirCallbacks() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val client = session(peer, Scx2Revision.PRESSURE, statuses)
            try {
                client.start(); val hello = peer.receive(Scx2Kind.HELLO); peer.send(peer.ready(hello))
                peer.send(peer.ack(peer.receive(Scx2Kind.STATE))); awaitReady(statuses)
                val results = LinkedBlockingQueue<Boolean>()
                client.submit(Scx2Output(buttons = 1, rightTrigger = 65535)) { results.offer(it) }
                val down = peer.receive(Scx2Kind.STATE)
                client.submit(Scx2Output(buttons = 2)) { results.offer(it) }
                client.submit(Scx2Output.NEUTRAL) { results.offer(it) }
                client.stop()
                val stop = peer.receive(Scx2Kind.STOP)
                assertEquals(down.sequence + 1uL, stop.sequence)
                repeat(3) { assertEquals(false, results.poll(500, TimeUnit.MILLISECONDS)) }
                assertEquals(Scx2Kind.STOP, peer.receive(Scx2Kind.STOP).kind)
            } finally { client.stop() }
        }
    }

    @Test fun pressureUnavailableFailsWithoutSendingAnyStateOrLegacyFallback() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val client = session(peer, Scx2Revision.PRESSURE, statuses)
            try {
                client.start(); val hello = peer.receive(Scx2Kind.HELLO)
                peer.send(peer.ready(hello).copy(hidReady = false, pressureCapability = false, usbButtonCapability = false, usbPressureCapability = false, challenge = Scx2Packet.ZERO))
                var stopped = false
                repeat(3) { if (statuses.poll(500, TimeUnit.MILLISECONDS) == Scx2ClientMachine.Phase.STOPPED) stopped = true }
                assertTrue(stopped)
                val callbacks = LinkedBlockingQueue<Boolean>()
                client.submit(Scx2Output(rightTrigger = 65535)) { callbacks.offer(it) }
                assertEquals(false, callbacks.poll(500, TimeUnit.MILLISECONDS))
                peer.socket.soTimeout = 100
                try { peer.receive(Scx2Kind.STATE); fail("Unexpected output") } catch (_: java.net.SocketTimeoutException) {}
            } finally { client.stop() }
        }
    }

    @Test fun excessiveUnacknowledgedIdenticalRequestsAlsoFailClosed() {
        Peer().use { peer ->
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val failures = LinkedBlockingQueue<Scx2ClientMachine.Failure>()
            val client = Scx2UdpSession("127.0.0.1", peer.socket.localPort, Scx2Revision.LEGACY,
                { it() }, { phase, failure -> statuses.offer(phase); failure?.let(failures::offer) })
            try {
                client.start(); val hello = peer.receive(Scx2Kind.HELLO); peer.send(peer.ready(hello))
                peer.send(peer.ack(peer.receive(Scx2Kind.STATE))); awaitReady(statuses)
                client.submit(Scx2Output(buttons = 1))
                peer.receive(Scx2Kind.STATE)
                // No ACK: identical commands cannot bypass bounded callback/work queues.
                repeat(300) { client.submit(Scx2Output(buttons = 1)) }
                peer.receive(Scx2Kind.STOP)
                assertEquals(Scx2ClientMachine.Failure.QUEUE_OVERFLOW, failures.poll(500, TimeUnit.MILLISECONDS))
            } finally { client.stop() }
        }
    }

    @Test fun callbacksQueuedForUiCannotReportReadyOrSuccessAfterStop() {
        Peer().use { peer ->
            val ui = LinkedBlockingQueue<() -> Unit>()
            val statuses = mutableListOf<Scx2ClientMachine.Phase>()
            val results = mutableListOf<Boolean>()
            val client = Scx2UdpSession("127.0.0.1", peer.socket.localPort, Scx2Revision.LEGACY,
                { ui.offer(it) }, { phase, _ -> statuses.add(phase) })
            try {
                client.start(); val hello = peer.receive(Scx2Kind.HELLO); peer.send(peer.ready(hello))
                peer.send(peer.ack(peer.receive(Scx2Kind.STATE)))
                // A new heartbeat proves neutral ACK processing without executing any UI callback.
                val heartbeat = peer.receive(Scx2Kind.STATE); peer.send(peer.ack(heartbeat))
                client.submit(Scx2Output(buttons = 1)) { results.add(it) }
                val down = peer.receive(Scx2Kind.STATE); assertEquals(1, down.output!!.buttons)
                peer.send(peer.ack(down))
                peer.receive(Scx2Kind.STATE) // next heartbeat proves the completion is queued
                client.stop(); peer.receive(Scx2Kind.STOP)
                while (true) { val callback = ui.poll() ?: break; callback() }
                assertFalse(statuses.contains(Scx2ClientMachine.Phase.READY))
                assertEquals(listOf(false), results)
            } finally { client.stop() }
        }
    }
    @Test fun replacementSocketDiscardsOldPressesWaitsForAvailabilityAndRejectsOldAck() {
        for (revision in Scx2Revision.values()) Peer().use { peer ->
            val oldStatuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val old = session(peer, revision, oldStatuses)
            val statuses = LinkedBlockingQueue<Scx2ClientMachine.Phase>()
            val fresh = Scx2UdpSession("127.0.0.1", peer.socket.localPort, revision, { it() },
                { phase, _ -> statuses.offer(phase) }, waitForAvailability = true)
            try {
                old.start(); val oldHello = peer.receive(Scx2Kind.HELLO); peer.send(peer.ready(oldHello))
                peer.send(peer.ack(peer.receive(Scx2Kind.STATE))); awaitReady(oldStatuses)
                old.submit(Scx2Output(buttons = 1)); val held = peer.receive(Scx2Kind.STATE)
                old.submit(Scx2Output(buttons = 2)); old.submit(Scx2Output.NEUTRAL)
                old.stop(); peer.receive(Scx2Kind.STOP)
                fresh.start(); val hello = peer.receive(Scx2Kind.HELLO)
                assertNotEquals(oldHello.session, hello.session)
                peer.send(peer.ready(hello).copy(hidReady = false, pressureCapability = false, usbButtonCapability = false, usbPressureCapability = false, challenge = Scx2Packet.ZERO))
                assertEquals(hello.session, peer.receive(Scx2Kind.HELLO).session)
                assertFalse(statuses.toList().contains(Scx2ClientMachine.Phase.READY))
                peer.send(peer.ready(hello))
                val neutral = peer.receive(Scx2Kind.STATE)
                assertEquals(Scx2Output.NEUTRAL, neutral.output); assertEquals(1uL, neutral.sequence)
                peer.send(peer.ack(held)) // old tuple delivered to the new socket
                assertEquals(neutral, peer.receive(Scx2Kind.STATE))
                assertFalse(statuses.toList().contains(Scx2ClientMachine.Phase.READY))
                peer.send(peer.ack(neutral)); awaitReady(statuses)
                assertEquals(Scx2Output.NEUTRAL, peer.receive(Scx2Kind.STATE).output)
            } finally { old.stop(); fresh.stop() }
        }
    }

}
