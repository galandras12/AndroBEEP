package com.galandras12.droidbeep.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Protocol constants, ported from core/Config.h and core/Version.h of BeeBEEP 5.9.2 (build 1607). */
object Proto {
    const val PROTOCOL_VERSION = 96
    const val VERSION_STRING = "5.9.2"
    const val QT_MAJOR = "5"

    /** QDataStream version we announce (DATASTREAM_VERSION_2 = Qt 5.x stream v12): makes both sides use SHA1 for the cipher key. */
    const val DATASTREAM_VERSION = 12

    const val DEFAULT_LISTENER_PORT = 6475
    const val DEFAULT_FILE_TRANSFER_PORT = 6476
    const val DEFAULT_BROADCAST_PORT = 36475
    const val MULTICAST_GROUP = "224.0.64.75"

    const val SECURE_LEVEL_2 = 60
    const val FILE_TRANSFER_2 = 63
    const val HIVE = 64
    const val NEW_GROUP = 67
    const val UTC_TIMESTAMP = 68
    const val SECURE_LEVEL_3 = 73
    const val FILE_TRANSFER_RESUME = 82
    const val FILE_TRANSFER_UTC_MODIFIED_DATE = 86
    const val SECURE_LEVEL_4 = 90
    const val VCARD_ROOM_LOCATION = 91
    const val RECEIVED_MESSAGE = 93
    const val NEW_GENERATION = 100

    const val ID_INVALID = 0L
    const val ID_START = 1000L
    const val ID_BEEP_MESSAGE = 11L
    const val ID_WRITING_MESSAGE = 12L
    const val ID_PING_MESSAGE = 13L
    const val ID_PONG_MESSAGE = 14L
    const val ID_HELLO_MESSAGE = 15L
    const val ID_USER_MESSAGE = 16L
    const val ID_SHARE_MESSAGE = 18L
    const val ID_SHAREBOX_MESSAGE = 19L
    const val ID_SHAREDESKTOP_MESSAGE = 21L
    const val ID_BUZZ_MESSAGE = 22L
    const val ID_TEST_MESSAGE = 24L
    const val ID_HELP_MESSAGE = 25L
    const val ID_RECEIVED_MESSAGE = 26L

    const val ENCRYPTED_DATA_BLOCK_SIZE = 16

    const val PARAGRAPH = ' ' // PROTOCOL_FIELD_SEPARATOR
    const val LINE = ' '      // DATA_FIELD_SEPARATOR

    const val DEFAULT_CHAT_NAME = "* BeeBEEP Chat *"
    val DEFAULT_CHAT_PRIVATE_ID: String get() = Crypto.sha1Hex(DEFAULT_CHAT_NAME)

    const val FILE_REFUSE_REASON_USER = "user"
    const val FILE_REFUSE_REASON_POLICY = "policy"
}

enum class MessageType(val header: String) {
    Undefined("BEE-BOOH"), Beep("BEE-BEEP"), Hello("BEE-CIAO"), Ping("BEE-PING"), Pong("BEE-PONG"),
    Chat("BEE-CHAT"), System("BEE-SYST"), User("BEE-USER"), File("BEE-FILE"), Share("BEE-FSHR"),
    Group("BEE-GROU"), Folder("BEE-FOLD"), Read("BEE-READ"), Hive("BEE-HIVE"), ShareBox("BEE-SBOX"),
    ShareDesktop("BEE-DESK"), Buzz("BEE-BUZZ"), Test("BEE-TEST"), Help("BEE-HELP"), Received("BEE-RECV");

    companion object {
        private val byHeader = entries.associateBy { it.header }
        fun fromHeader(h: String): MessageType = byHeader[h] ?: Undefined
    }
}

/** Bit positions of Message::Flag (the order must never change). */
object Flag {
    const val Private = 0
    const val UserWriting = 1
    const val UserStatus = 2
    const val Create = 3
    const val UserVCard = 4
    const val Refused = 5
    const val List = 6
    const val Request = 7
    const val GroupChat = 8
    const val Delete = 9
    const val Auto = 10
    const val Important = 11
    const val VoiceMessage = 12
    const val EncryptionDisabled = 13
    const val Compressed = 14
    const val Delayed = 15
    const val SourceCode = 16
}

class BeeMessage(
    var type: MessageType,
    var id: Long,
    var text: String = "",
    var data: String = "",
    var flags: Int = 0,
    var timestamp: Instant = Instant.now()
) {
    val isValid: Boolean get() = type != MessageType.Undefined
    fun addFlag(f: Int): BeeMessage { flags = flags or (1 shl f); return this }
    fun hasFlag(f: Int): Boolean = flags != 0 && (flags and (1 shl f)) != 0
    override fun toString(): String = "BeeMessage($type,#$id,flags=$flags,data='${data.take(60)}',text='${text.take(60)}')"
}

object MessageCodec {
    private val isoUtc: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val isoLocal: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun isoUtc(t: Instant): String = isoUtc.format(t)
    fun isoLocal(t: Instant): String = isoLocal.format(t.atZone(ZoneId.systemDefault()))

    /** Qt::ISODate parsing (with optional zone designator and fractional seconds). */
    fun parseIso(s: String, assumeUtc: Boolean): Instant? {
        val t = s.trim()
        if (t.length < 10) return null
        return try {
            if (t.endsWith("Z") || Regex(".*[+-]\\d\\d:?\\d\\d$").matches(t) && t.length > 19) {
                OffsetDateTime.parse(if (t.matches(Regex(".*[+-]\\d\\d\\d\\d$"))) t.substring(0, t.length - 2) + ":" + t.substring(t.length - 2) else t).toInstant()
            } else {
                val ldt = if (t.length == 10) java.time.LocalDate.parse(t).atStartOfDay() else LocalDateTime.parse(t)
                if (assumeUtc) ldt.toInstant(ZoneOffset.UTC) else ldt.atZone(ZoneId.systemDefault()).toInstant()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Protocol::fromMessage */
    fun encode(m: BeeMessage, protoVersion: Int): ByteArray {
        if (!m.isValid) return ByteArray(0)
        val sb = StringBuilder()
        val sep = Proto.PARAGRAPH
        sb.append(m.type.header).append(sep)
        sb.append(m.id).append(sep)
        sb.append(m.text.length).append(sep)
        sb.append(m.flags).append(sep)
        sb.append(m.data).append(sep)
        sb.append(if (protoVersion < Proto.UTC_TIMESTAMP) isoLocal(m.timestamp) else isoUtc(m.timestamp)).append(sep)
        sb.append(m.text)
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val padded = (bytes.size + Proto.ENCRYPTED_DATA_BLOCK_SIZE - 1) / Proto.ENCRYPTED_DATA_BLOCK_SIZE * Proto.ENCRYPTED_DATA_BLOCK_SIZE
        if (padded == bytes.size) return bytes
        val out = bytes.copyOf(padded)
        for (i in bytes.size until padded) out[i] = ' '.code.toByte()
        return out
    }

    /** Protocol::toMessage; returns a message of type Undefined when data is invalid. */
    fun decode(bytes: ByteArray, protoVersion: Int): BeeMessage {
        val invalid = BeeMessage(MessageType.Undefined, 0)
        val s = String(bytes, Charsets.UTF_8)
        val parts = s.split(Proto.PARAGRAPH).toMutableList()
        if (parts.size < 7) return invalid
        val type = MessageType.fromHeader(parts.removeAt(0))
        if (type == MessageType.Undefined) return invalid
        val id = parts.removeAt(0).trim().toLongOrNull() ?: return invalid
        if (id == Proto.ID_INVALID) return invalid
        val size = parts.removeAt(0).trim().toIntOrNull() ?: return invalid
        val flags = parts.removeAt(0).trim().toIntOrNull() ?: return invalid
        val data = parts.removeAt(0)
        val ts = parseIso(parts.removeAt(0), protoVersion >= Proto.UTC_TIMESTAMP) ?: return invalid
        var text = if (parts.size > 1) parts.joinToString(Proto.PARAGRAPH.toString()) else if (parts.size == 1) parts[0] else ""
        if (text.length > size) text = text.substring(0, size) // drop the spaces added for encryption
        return BeeMessage(type, id, text, data, flags, ts)
    }
}
