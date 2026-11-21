package com.example.simplecontroller.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Invoke only from an I/O worker. All routing belongs to the retired connection. */
internal fun flushAndCloseReceiverSocket(
    socket: DatagramSocket,
    address: InetAddress?,
    port: Int,
    release: String?,
    disconnect: Boolean
) {
    try {
        if (address == null) return
        fun send(message: String) {
            val bytes = message.toByteArray()
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, port)) }
        }
        if (release != null) repeat(3) { send(release) }
        if (disconnect) send("DISCONNECT")
    } finally { socket.close() }
}
