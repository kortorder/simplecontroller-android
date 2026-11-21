package com.example.simplecontroller.net

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android UDP and lease timers, using only a loopback fake Windows receiver. */
@RunWith(AndroidJUnit4::class)
class PhoneReceiverLeaseInstrumentedTest {
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        val received = CopyOnWriteArrayList<String>()
        @Volatile var leaseAck = true
        @Volatile var foreignLeaseAck = false
        @Volatile var holdNextLease = false
        @Volatile var heldLeaseSequence: Long? = null
        @Volatile var releaseBarrier = -1L
        @Volatile var dropRelease = false
        @Volatile var running = true
        private val worker = Thread {
            while (running) try {
                val p = DatagramPacket(ByteArray(4096), 4096); socket.receive(p)
                val raw = String(p.data, 0, p.length)
                val text = if (raw.startsWith("player")) raw.substringAfter(':') else raw
                received.add(text)
                val fields = text.split(':')
                if (dropRelease && text.startsWith("RELEASE_ALL:")) continue
                if (text.startsWith("RELEASE_ALL:")) releaseBarrier = maxOf(releaseBarrier, fields[2].toLong())
                if (text.startsWith("PHONE_LEASE_V1:") && holdNextLease) {
                    holdNextLease = false; heldLeaseSequence = fields[2].toLong()
                }
                val reply = when {
                    text == "PING" -> "PONG"
                    text.startsWith("PHONE_CAPS_V1:") -> "PHONE_CAPS_V1_ACK:${fields[1]}:${fields[2]}:PHONE_OUTPUT_V1"
                    text.startsWith("PHONE_LEASE_V1:") && leaseAck && fields[2].toLong() > releaseBarrier && fields[2].toLong() != heldLeaseSequence -> "PHONE_LEASE_V1_ACK:${fields[1]}:${fields[2]}:3000"
                    text.startsWith("PHONE_ACTIVATE_V1:") || text.startsWith("PHONE_STATE_V1:") || text.startsWith("PHONE_END_V1:") -> "PHONE_ACK_V1:${fields[1]}:${fields[2]}"
                    text.startsWith("RELEASE_ALL:") -> "RELEASE_ALL_ACK:${fields[1]}:${fields[2]}"
                    else -> null
                }
                if (reply != null) { val bytes = reply.toByteArray(); socket.send(DatagramPacket(bytes, bytes.size, p.address, p.port)) }
                if (foreignLeaseAck && text.startsWith("PHONE_LEASE_V1:")) {
                    val bytes = "PHONE_LEASE_V1_ACK:${fields[1]}:${fields[2]}:3000".toByteArray()
                    DatagramSocket().use { foreign -> foreign.send(DatagramPacket(bytes, bytes.size, p.address, p.port)) }
                }
            } catch (_: SocketTimeoutException) {} catch (e: Exception) { if (running) throw AssertionError(e) }
        }.apply { isDaemon = true; start() }
        fun count(prefix: String) = received.count { it.startsWith(prefix) }
        override fun close() { running = false; socket.close(); worker.join(1000) }
    }
    @After fun cleanup() = main {
        NetworkClient.close(); UdpClient.close(); NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
        NetworkClient.appDidEnterForeground()
    }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; SystemClock.sleep(20) }
        fail(message)
    }
    private fun connect(peer: Peer) {
        main {
            NetworkClient.close(); UdpClient.close()
            NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
            NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
            NetworkClient.appDidEnterForeground()
            NetworkClient.updateSettings("127.0.0.1", peer.socket.localPort, false)
            UdpClient.initialize("127.0.0.1", peer.socket.localPort)
        }
        SystemClock.sleep(150)
        main { NetworkClient.start() }
        try { waitFor("PC inputs never became ready") { UdpClient.floatingSupported && !UdpClient.activationPending } }
        catch (error: AssertionError) { throw AssertionError("${error.message}; status=${NetworkClient.connectionStatus.value}; error=${NetworkClient.lastErrorMessage.value}; packets=${peer.received.takeLast(15)}", error) }
    }
    @Test fun leaseRenewsSurvivesReleaseAllAndLostAckClosesInput() = Peer().use { peer ->
        connect(peer)
        assertTrue(peer.count("PHONE_LEASE_V1:") >= 1)
        main { UdpClient.sendCommand("X360A_DOWN") }
        waitFor("No ordered A press") { peer.received.any { it.startsWith("OUTPUT:") && it.endsWith("X360A_DOWN") } }
        main { UdpClient.prepareReleaseAll(); UdpClient.sendReleaseAll {} }
        waitFor("Release All stopped the connection lease") { peer.count("PHONE_LEASE_V1:") >= 4 }
        assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
        peer.leaseAck = false
        waitFor("Missing lease ACK did not close inputs") { NetworkClient.connectionStatus.value == NetworkClient.ConnectionStatus.DISCONNECTED }
        assertFalse(UdpClient.floatingSupported)
        assertTrue(NetworkClient.lastErrorMessage.value.orEmpty().contains("stopped replying"))
        val before = peer.received.size
        main { UdpClient.sendCommand("X360B_DOWN") }
        SystemClock.sleep(250)
        assertFalse(peer.received.drop(before).any { it.endsWith("X360B_DOWN") })
        peer.leaseAck = true
        val previousActivations = peer.count("PHONE_ACTIVATE_V1:")
        main { NetworkClient.start() }
        waitFor("Fresh activation never became ready") { UdpClient.floatingSupported && !UdpClient.activationPending }
        assertTrue(peer.count("PHONE_ACTIVATE_V1:") > previousActivations)
        assertFalse(NetworkClient.lastErrorMessage.value.orEmpty().contains("stopped replying"))
    }
    @Test fun releaseBarrierRetiresUnacknowledgedLeaseWithoutDisconnecting() = Peer().use { peer ->
        connect(peer)
        peer.holdNextLease = true
        waitFor("No renewal to withhold") { peer.heldLeaseSequence != null }
        main { UdpClient.prepareReleaseAll(); UdpClient.sendReleaseAll {} }
        waitFor("Release barrier never arrived") { peer.releaseBarrier > peer.heldLeaseSequence!! }
        waitFor("Fresh higher lease never became ready") { UdpClient.floatingSupported && !UdpClient.activationPending &&
            peer.received.any { it.startsWith("PHONE_LEASE_V1:") && it.split(':')[2].toLong() > peer.releaseBarrier } }
        SystemClock.sleep(800) // The retired request's timeout must not disconnect the fresh lease.
        assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
        assertTrue(UdpClient.floatingSupported)
    }
    @Test fun lostReleaseNeverRenewsOldHeldInput() = Peer().use { peer ->
        connect(peer)
        peer.dropRelease = true
        val leases = peer.count("PHONE_LEASE_V1:")
        main { UdpClient.prepareReleaseAll(); UdpClient.sendReleaseAll {} }
        waitFor("Lost release left the connection enabled") { NetworkClient.connectionStatus.value == NetworkClient.ConnectionStatus.DISCONNECTED }
        assertEquals(leases, peer.count("PHONE_LEASE_V1:"))
        assertFalse(UdpClient.floatingSupported)
    }
    @Test fun changedEndpointFlushesOnlyRetiredSocketBeforeFreshConnection() = Peer().use { old ->
        Peer().use { fresh ->
            connect(old)
            main {
                UdpClient.prepareReleaseAll(); UdpClient.sendReleaseAll {}
                NetworkClient.updateSettings("127.0.0.1", fresh.socket.localPort, false)
                UdpClient.updateSettings("127.0.0.1", fresh.socket.localPort)
            }
            waitFor("New endpoint did not activate") { UdpClient.floatingSupported && !UdpClient.activationPending }
            waitFor("Retired socket never flushed release and disconnect") { old.count("RELEASE_ALL:") >= 1 && old.received.contains("DISCONNECT") }
            assertEquals(0, fresh.count("RELEASE_ALL:"))
            assertFalse(fresh.received.contains("DISCONNECT"))
            assertTrue(UdpClient.floatingSupported)
        }
    }
    @Test fun changedEndpointRetiresAnUnfinishedConnectionAttempt() = Peer().use { old ->
        Peer().use { fresh ->
            main {
                NetworkClient.close(); UdpClient.close()
                NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
                NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
                NetworkClient.appDidEnterForeground()
                NetworkClient.updateSettings("127.0.0.1", old.socket.localPort, false)
                UdpClient.initialize("127.0.0.1", old.socket.localPort)
                NetworkClient.start()
                assertEquals(NetworkClient.ConnectionStatus.CONNECTING, NetworkClient.connectionStatus.value)
                NetworkClient.updateSettings("127.0.0.1", fresh.socket.localPort, false)
                UdpClient.updateSettings("127.0.0.1", fresh.socket.localPort)
            }
            waitFor("Replacement endpoint did not activate") { UdpClient.floatingSupported && !UdpClient.activationPending }
            assertEquals(0, old.count("PHONE_ACTIVATE_V1:"))
            assertTrue(fresh.count("PHONE_LEASE_V1:") >= 1)
        }
    }
    @Test fun olderReceiverFallsBackWithVisibleWarning() = Peer().use { peer ->
        peer.leaseAck = false
        peer.foreignLeaseAck = true // A matching response from another port cannot confirm protection.
        connect(peer)
        assertTrue(NetworkClient.lastErrorMessage.value.orEmpty().contains("older PC receiver"))
        main { UdpClient.sendCommand("X360A_DOWN") }
        waitFor("Legacy receiver lost original inputs") { peer.received.any { it.endsWith("X360A_DOWN") } }
    }
    @Test fun backgroundStopsLeaseAndReturnRequiresFreshNeutralActivation() = Peer().use { peer ->
        connect(peer)
        main { NetworkClient.appDidEnterBackground() }
        val count = peer.count("PHONE_LEASE_V1:")
        SystemClock.sleep(1300)
        assertEquals(count, peer.count("PHONE_LEASE_V1:"))
        assertFalse(UdpClient.floatingSupported)
        val activations = peer.count("PHONE_ACTIVATE_V1:")
        main { NetworkClient.appDidEnterForeground() }
        waitFor("Return never established fresh activation") { UdpClient.floatingSupported && !UdpClient.activationPending }
        assertTrue(peer.count("PHONE_ACTIVATE_V1:") > activations)
    }
}
