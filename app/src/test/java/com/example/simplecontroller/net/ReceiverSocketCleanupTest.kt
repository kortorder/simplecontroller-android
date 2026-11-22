package com.example.simplecontroller.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class ReceiverSocketCleanupTest {
    @Test fun capturedSocketFlushesBarrierThenDisconnectAndCloses() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { peer ->
            peer.soTimeout = 2000
            val retiring = DatagramSocket(0, loopback)
            val port = retiring.localPort
            flushAndCloseReceiverSocket(retiring, loopback, peer.localPort, "RELEASE_ALL:session:4", true)
            val messages = List(4) {
                val p = DatagramPacket(ByteArray(512), 512); peer.receive(p)
                assertEquals(port, p.port)
                String(p.data, 0, p.length)
            }
            assertEquals(listOf("RELEASE_ALL:session:4", "RELEASE_ALL:session:4", "RELEASE_ALL:session:4", "DISCONNECT"), messages)
            assertTrue(retiring.isClosed)
        }
    }
    @Test fun absentDestinationStillClosesRetiredSocket() {
        val retiring = DatagramSocket()
        flushAndCloseReceiverSocket(retiring, null, 42734, null, false)
        assertTrue(retiring.isClosed)
    }
}
