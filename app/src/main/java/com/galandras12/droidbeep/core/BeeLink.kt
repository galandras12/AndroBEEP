package com.galandras12.droidbeep.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One TCP link with a BeeBEEP peer (a chat connection or a file transfer connection).
 * Implements the HELLO exchange of ConnectionSocket (ECDH key agreement + AES-256 block cipher)
 * and the block framing, with blocking I/O: call [handshake] first, then [send] / [receive].
 */
class BeeLink(
    val socket: Socket,
    private val identity: () -> LocalIdentity,
    private val passwordHash: () -> ByteArray,
    val initiator: Boolean,
) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val writeLock = Any()

    @Volatile var protoVersion: Int = 1
        private set
    @Volatile var datastreamVersion: Int = 0
        private set
    @Volatile var encrypted: Boolean = true
        private set
    @Volatile private var cipherKey: ByteArray = ByteArray(0)
    @Volatile var peer: PeerHello? = null
        private set
    @Volatile var isNewGenerationPeer: Boolean = false
        private set
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set
    @Volatile var lastSendMs: Long = System.currentTimeMillis()
        private set

    val peerHostAddress: String get() = socket.inetAddress?.hostAddress ?: ""
    val remotePort: Int get() = socket.port

    private val keyForCipher: ByteArray get() = if (cipherKey.isEmpty()) passwordHash() else cipherKey

    // ---- HELLO ---------------------------------------------------------------------------

    private fun sendHello(pub: String) {
        val bytes = MessageCodec.encode(Hello.build(identity(), pub, passwordHash()), 1)
        val enc = Crypto.encrypt(bytes, passwordHash(), 1)
        synchronized(writeLock) { Wire.write(output, enc, 1) }
        lastSendMs = System.currentTimeMillis()
    }

    private fun readHello(): BeeMessage {
        val raw = Wire.read(input, 1)
        lastActivityMs = System.currentTimeMillis()
        val plain = Crypto.decrypt(raw, passwordHash(), 1)
        val m = MessageCodec.decode(plain, 1)
        if (!m.isValid) throw IOException("Invalid HELLO received")
        return m
    }

    /**
     * Performs the HELLO exchange, derives the cipher key and returns the authenticated peer data.
     * Throws [IOException] if the peer is not a valid BeeBEEP peer or the network password differs.
     */
    fun handshake(): PeerHello {
        val keys = EcdhKeys()
        val myPub = keys.publicKey
        val helloMsg: BeeMessage
        if (initiator) {
            sendHello(myPub)
            helloMsg = readHello()
        } else {
            helloMsg = readHello()
            sendHello(myPub)
        }
        if (helloMsg.type == MessageType.Test) throw IOException("Connection test request")
        if (helloMsg.type != MessageType.Hello) throw IOException("HELLO expected but ${helloMsg.type} arrived")
        val hello = Hello.parse(helloMsg) ?: throw IOException("Invalid HELLO fields")

        // authentication with the shared network password
        if (hello.authHash != Crypto.hash(hello.name, passwordHash())) throw IOException("HELLO has an invalid password")

        var announced = hello.announcedProtocol
        if (announced >= Proto.NEW_GENERATION) {
            isNewGenerationPeer = true
            announced = Proto.PROTOCOL_VERSION
        } else if (announced > Proto.PROTOCOL_VERSION) {
            announced = Proto.PROTOCOL_VERSION
        }
        protoVersion = announced
        datastreamVersion = if (hello.datastreamVersion > 0) minOf(hello.datastreamVersion, Proto.DATASTREAM_VERSION) else 0
        encrypted = !hello.encryptionDisabled

        if (encrypted && protoVersion > Proto.SECURE_LEVEL_2 && hello.publicKey.isNotEmpty()) {
            val key: ByteArray = if (protoVersion >= Proto.SECURE_LEVEL_4) {
                val shared = keys.generateSharedKey(hello.publicKey) ?: throw IOException("Invalid ECDH public key")
                Crypto.createCipherKey(shared, datastreamVersion)
            } else {
                val first = if (initiator) myPub else hello.publicKey
                val second = if (initiator) hello.publicKey else myPub
                Crypto.createCipherKey((first + second).toByteArray(Charsets.UTF_8), datastreamVersion)
            }
            if (key.isEmpty()) throw IOException("Unable to create the cipher key")
            cipherKey = key
        }
        peer = hello
        lastActivityMs = System.currentTimeMillis()
        return hello
    }

    // ---- regular traffic -----------------------------------------------------------------

    fun sendBytes(plain: ByteArray) {
        val data = if (encrypted) Crypto.encrypt(plain, keyForCipher, protoVersion) else plain
        synchronized(writeLock) { Wire.write(output, data, protoVersion) }
        lastSendMs = System.currentTimeMillis()
    }

    /** Returns false on I/O error (the link should then be closed). */
    fun send(m: BeeMessage): Boolean = try {
        sendBytes(MessageCodec.encode(m, protoVersion))
        true
    } catch (e: IOException) {
        false
    }

    fun receiveBytes(): ByteArray {
        val raw = Wire.read(input, protoVersion)
        lastActivityMs = System.currentTimeMillis()
        return if (encrypted) Crypto.decrypt(raw, keyForCipher, protoVersion) else raw
    }

    /** Next message; invalid or unknown messages come back as type [MessageType.Undefined]. */
    fun receive(): BeeMessage = MessageCodec.decode(receiveBytes(), protoVersion)

    fun sendPing() = send(BeeMessage(MessageType.Ping, Proto.ID_PING_MESSAGE, "*"))
    fun sendPong() = send(BeeMessage(MessageType.Pong, Proto.ID_PONG_MESSAGE, "*"))

    fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        fun connect(host: String, port: Int, timeoutMs: Int, identity: () -> LocalIdentity, passwordHash: () -> ByteArray): BeeLink {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return BeeLink(s, identity, passwordHash, true)
        }
    }
}
