package com.galandras12.droidbeep.core

import java.time.Instant

/** The identity this device announces to BeeBEEP peers. */
data class LocalIdentity(
    var nickname: String,
    var accountName: String,
    var domainName: String,
    var localHostName: String,
    var hash: String,
    var color: String = "#2E7D32",
    var status: Int = UserStatus.Online,
    var statusDescription: String = "",
    var statusChangedIn: Instant? = null,
    var workgroups: List<String> = emptyList(),
    var listenerPort: Int = Proto.DEFAULT_LISTENER_PORT,
)

object UserStatus {
    const val Offline = 0
    const val Online = 1
    const val Busy = 2
    const val Away = 3
    const val NumStatus = 4
}

/** What a remote peer tells in its HELLO message. */
data class PeerHello(
    val listenerPort: Int,
    val name: String,
    val status: Int,
    val statusDescription: String,
    val accountName: String,
    val publicKey: String,
    val version: String,
    val hash: String,
    val color: String,
    val workgroups: List<String>,
    val qtVersion: String,
    val datastreamVersion: Int,
    val statusChangedIn: Instant?,
    val domainName: String,
    val localHostName: String,
    val announcedProtocol: Int,
    val encryptionDisabled: Boolean,
    val compressed: Boolean,
    val authHash: String,
)

object Hello {
    fun build(id: LocalIdentity, publicKey: String, passwordHash: ByteArray): BeeMessage {
        val d = Proto.LINE.toString()
        val fields = listOf(
            id.listenerPort.toString(),
            id.nickname,
            id.status.toString(),
            id.statusDescription,
            id.accountName,
            publicKey,
            Proto.VERSION_STRING,
            id.hash,
            id.color,
            id.workgroups.joinToString(", "),
            Proto.QT_MAJOR,
            Proto.DATASTREAM_VERSION.toString(),
            id.statusChangedIn?.let { MessageCodec.isoLocal(it) } ?: "",
            id.domainName,
            id.localHostName,
        )
        val m = BeeMessage(MessageType.Hello, Proto.PROTOCOL_VERSION.toLong(), fields.joinToString(d))
        m.data = Crypto.hash(id.nickname, passwordHash)
        return m
    }

    fun parse(m: BeeMessage): PeerHello? {
        if (m.type != MessageType.Hello) return null
        val sl = m.text.split(Proto.LINE)
        if (sl.size < 4) return null
        val port = sl[0].toIntOrNull() ?: return null
        fun f(i: Int) = sl.getOrNull(i) ?: ""
        val status = f(2).toIntOrNull()?.let { if (it == UserStatus.Offline) UserStatus.Online else it } ?: UserStatus.Online
        val workgroups = f(9).split(", ").filter { it.isNotEmpty() }
        val changed = f(12).takeIf { it.isNotEmpty() }?.let { MessageCodec.parseIso(it, false) }
        return PeerHello(
            listenerPort = port,
            name = f(1),
            status = status,
            statusDescription = f(3),
            accountName = f(4).ifEmpty { f(1) },
            publicKey = f(5),
            version = f(6),
            hash = f(7),
            color = f(8).ifEmpty { "#000000" },
            workgroups = workgroups,
            qtVersion = f(10),
            datastreamVersion = f(11).toIntOrNull() ?: 0,
            statusChangedIn = changed,
            domainName = f(13),
            localHostName = f(14),
            announcedProtocol = if (m.id <= Proto.ID_HELLO_MESSAGE) 1 else m.id.toInt(),
            encryptionDisabled = m.hasFlag(Flag.EncryptionDisabled),
            compressed = m.hasFlag(Flag.Compressed),
            authHash = m.data,
        )
    }
}
