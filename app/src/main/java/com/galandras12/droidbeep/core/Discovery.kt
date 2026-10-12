package com.galandras12.droidbeep.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * UDP discovery used by BeeBEEP (default port 36475): every client sends a BEE-BEEP datagram, in clear text,
 * to the subnet broadcast addresses and to the multicast group 224.0.64.75; the datagram text is the TCP
 * listener port of the sender. The receiver connects to the sender on that port.
 */
class Discovery(
    private val listenerPort: () -> Int,
    private val broadcastPort: Int,
    private val useMulticast: Boolean,
    private val onPeerFound: (host: String, port: Int) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        try {
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(broadcastPort))
            socket = s
            if (useMulticast) joinMulticast(s)
        } catch (e: Exception) {
            log("Discovery: cannot bind UDP port $broadcastPort: ${e.message}")
            socket = null
        }
        thread = Thread({ receiveLoop() }, "bee-discovery").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun joinMulticast(s: MulticastSocket) {
        val group = InetAddress.getByName(Proto.MULTICAST_GROUP)
        var joined = 0
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                try {
                    s.joinGroup(InetSocketAddress(group, broadcastPort), ni)
                    joined++
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        if (joined == 0) {
            try { @Suppress("DEPRECATION") s.joinGroup(group) } catch (_: Exception) {}
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val s = socket ?: return
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (p.length <= 10) continue
                val m = MessageCodec.decode(p.data.copyOf(p.length), 1)
                if (!m.isValid || m.type != MessageType.Beep) continue
                val port = m.text.trim().toIntOrNull() ?: continue
                val host = p.address.hostAddress ?: continue
                onPeerFound(host.substringBefore('%'), port)
            } catch (e: Exception) {
                if (running) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    /** Sends our beacon to every broadcast address and the multicast group. */
    fun announce(extraTargets: List<Pair<String, Int>> = emptyList()) {
        val s = socket ?: return
        val port = listenerPort()
        val targets = LinkedHashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) targets += b.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {
        }
        targets += "255.255.255.255"
        for (t in targets) sendTo(s, t, broadcastPort, port)
        if (useMulticast) sendTo(s, Proto.MULTICAST_GROUP, broadcastPort, port)
        for ((h, p) in extraTargets) sendTo(s, h, p, port)
    }

    private fun sendTo(s: DatagramSocket, host: String, dstPort: Int, listener: Int) {
        try {
            val addr = InetAddress.getByName(host)
            val data = MessageCodec.encode(Msgs.beep(listener, host), 1)
            s.send(DatagramPacket(data, data.size, addr, dstPort))
        } catch (_: Exception) {
        }
    }

    companion object {
        /** All IPv4 addresses of this device. */
        fun localAddresses(): Set<String> {
            val out = HashSet<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    for (a in ni.inetAddresses) if (a is Inet4Address) out += a.hostAddress ?: continue
                }
            } catch (_: Exception) {
            }
            return out
        }
    }
}
