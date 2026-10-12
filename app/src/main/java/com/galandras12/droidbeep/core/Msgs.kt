package com.galandras12.droidbeep.core

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Builders and parsers for every BeeBEEP message type we speak (ports of the Protocol:: functions). */
object Msgs {
    private val L = Proto.LINE.toString()
    private val P = Proto.PARAGRAPH.toString()

    private val idCounter = AtomicLong(Proto.ID_START + (1..100).random())
    fun newId(): Long = idCounter.incrementAndGet()

    // ---- chat --------------------------------------------------------------------------

    data class ChatData(val textColor: String = "", val groupId: String = "", val groupName: String = "", val groupLastModified: Instant? = null)

    fun chatDataToString(c: ChatData): String = listOf(
        c.textColor, c.groupId, c.groupName, c.groupLastModified?.let { MessageCodec.isoUtc(it) } ?: ""
    ).joinToString(L)

    fun chatDataFrom(m: BeeMessage): ChatData {
        if (m.data.isEmpty()) return ChatData()
        val sl = m.data.split(Proto.LINE)
        return ChatData(
            textColor = sl.getOrElse(0) { "" },
            groupId = sl.getOrElse(1) { "" },
            groupName = sl.getOrElse(2) { "" },
            groupLastModified = sl.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { MessageCodec.parseIso(it, true) },
        )
    }

    fun chat(chat: Chat, text: String): BeeMessage {
        val m = BeeMessage(MessageType.Chat, newId(), text)
        val cd: ChatData
        when (chat.type) {
            ChatType.GROUP -> { m.addFlag(Flag.GroupChat); cd = ChatData(groupId = chat.privateId) }
            ChatType.PRIVATE -> { m.addFlag(Flag.Private); cd = ChatData() }
            ChatType.DEFAULT -> cd = ChatData()
        }
        m.data = chatDataToString(cd)
        return m
    }

    fun chatRead(chat: Chat): BeeMessage {
        val m = chat(chat, "")
        m.type = MessageType.Read
        return m
    }

    fun received(of: BeeMessage): BeeMessage =
        BeeMessage(MessageType.Received, Proto.ID_RECEIVED_MESSAGE, of.id.toString()).addFlag(Flag.Request)

    fun writing(chatPrivateId: String): BeeMessage {
        val m = BeeMessage(MessageType.User, Proto.ID_WRITING_MESSAGE, "*")
        m.addFlag(Flag.Private).addFlag(Flag.UserWriting)
        if (chatPrivateId.isNotEmpty()) m.data = chatPrivateId
        return m
    }

    fun buzz() = BeeMessage(MessageType.Buzz, Proto.ID_BUZZ_MESSAGE, "*")

    fun helpRequest(text: String): BeeMessage {
        val m = BeeMessage(MessageType.Help, Proto.ID_HELP_MESSAGE, text)
        m.addFlag(Flag.Request).addFlag(Flag.Private).addFlag(Flag.Important)
        return m
    }

    fun helpAnswer(text: String): BeeMessage {
        val m = BeeMessage(MessageType.Help, Proto.ID_HELP_MESSAGE, text)
        m.addFlag(Flag.Create).addFlag(Flag.Private).addFlag(Flag.Important)
        return m
    }

    // ---- user --------------------------------------------------------------------------

    fun userStatus(status: Int, description: String): BeeMessage {
        val m = BeeMessage(MessageType.User, Proto.ID_USER_MESSAGE, description)
        m.addFlag(Flag.UserStatus)
        m.data = status.toString()
        return m
    }

    fun vcard(vc: VCard, color: String, protoVersion: Int, helper: Boolean): BeeMessage {
        val m = BeeMessage(MessageType.User, Proto.ID_USER_MESSAGE, vc.photoPngBase64)
        m.addFlag(Flag.UserVCard)
        val d = mutableListOf(vc.nickName, vc.firstName, vc.lastName, vc.birthday, vc.email, color, vc.phone)
        if (protoVersion >= Proto.VCARD_ROOM_LOCATION) {
            d += vc.roomLocation
            d += if (helper) "H" else "N"
        }
        d += vc.info
        m.data = d.joinToString(L)
        return m
    }

    /** Returns the new vCard, the colour announced and the helper flag. */
    data class VCardData(val vcard: VCard, val color: String?, val helper: Boolean?)

    fun vcardFrom(m: BeeMessage, peerProtocol: Int): VCardData? {
        val sl = m.data.split(Proto.LINE).toMutableList()
        if (sl.size < 5) return null
        fun take() = if (sl.isEmpty()) "" else sl.removeAt(0)
        val nick = take(); val first = take(); val last = take(); val birthday = take(); val email = take()
        var color: String? = null
        if (sl.isNotEmpty()) color = take()
        val phone = if (sl.isNotEmpty()) take() else ""
        var room = ""
        var helper: Boolean? = null
        if (peerProtocol >= Proto.VCARD_ROOM_LOCATION) {
            if (sl.isNotEmpty()) room = take()
            if (sl.isNotEmpty()) helper = take().startsWith("H")
        }
        val info = if (sl.isNotEmpty()) take() else ""
        return VCardData(VCard(nick, first, last, birthday, email, phone, room, info, m.text.trim()), color, helper)
    }

    // ---- groups ------------------------------------------------------------------------

    data class MemberRecord(val name: String, val account: String, val hash: String, val domain: String)

    fun groupRequest(chat: Chat, toPeer: Peer, members: List<Peer>, protoVersion: Int): BeeMessage {
        val m = BeeMessage(MessageType.Group, newId(), "").addFlag(Flag.Request)
        val others = members.filter { it.key != toPeer.key }
        if (protoVersion < Proto.NEW_GROUP) {
            // obsolete format: user paths "name@host:port"
            if (others.isNotEmpty()) m.text = others.joinToString(P) { "${it.name}@${it.host}:${it.port}" }
            m.data = chatDataToString(ChatData(groupId = chat.privateId, groupName = chat.name))
        } else {
            if (others.isNotEmpty()) {
                val sl = mutableListOf(others.size.toString())
                for (u in others) { sl += u.name; sl += u.account; sl += u.hash; sl += u.domain }
                m.text = sl.joinToString(P)
            }
            m.data = chatDataToString(ChatData(groupId = chat.privateId, groupName = chat.name, groupLastModified = chat.lastModified))
        }
        return m
    }

    fun groupRefuse(groupId: String, groupName: String): BeeMessage {
        val m = BeeMessage(MessageType.Group, newId(), "").addFlag(Flag.Refused)
        m.data = chatDataToString(ChatData(groupId = groupId, groupName = groupName))
        return m
    }

    fun groupRemoveUser(chat: Chat): BeeMessage {
        val m = BeeMessage(MessageType.Group, newId(), "").addFlag(Flag.Delete)
        m.data = chatDataToString(ChatData(groupId = chat.privateId, groupName = chat.name, groupLastModified = chat.lastModified))
        return m
    }

    fun memberRecordsFrom(m: BeeMessage): List<MemberRecord> {
        if (m.text.isEmpty()) return emptyList()
        val sl = m.text.split(Proto.PARAGRAPH).toMutableList()
        val n = sl.removeAt(0).trim().toIntOrNull() ?: return emptyList()
        val out = mutableListOf<MemberRecord>()
        repeat(n) {
            if (sl.size >= 4) {
                out += MemberRecord(sl.removeAt(0), sl.removeAt(0), sl.removeAt(0), sl.removeAt(0))
            } else return emptyList()
        }
        return out
    }

    // ---- files -------------------------------------------------------------------------

    fun fileInfoToMessage(fi: BeeFileInfo, protoVersion: Int, newGenerationPeer: Boolean = false): BeeMessage {
        val m = BeeMessage(MessageType.File, newId(), fi.name)
        val lm = fi.lastModified
        val sl = mutableListOf(
            fi.hostPort.toString(),
            fi.size.toString(),
            fi.id.toString(),
            fi.password,
            fi.fileHash,
            fi.shareFolder,
            if (fi.inShareBox) "1" else "0",
            fi.chatPrivateId,
            when {
                lm == null -> ""
                protoVersion >= Proto.FILE_TRANSFER_UTC_MODIFIED_DATE -> MessageCodec.isoUtc(lm)
                else -> MessageCodec.isoLocal(lm)
            },
            fi.mimeType,
            fi.contentType.toString(),
            fi.startingPosition.toString(),
            fi.duration.toString(),
        )
        if (newGenerationPeer) sl += ""
        m.data = sl.joinToString(L)
        m.addFlag(Flag.Private)
        if (fi.isVoiceMessage) m.addFlag(Flag.VoiceMessage)
        return m
    }

    fun fileInfoRefused(fi: BeeFileInfo, protoVersion: Int, newGen: Boolean, reason: String): BeeMessage {
        val m = fileInfoToMessage(fi, protoVersion, newGen)
        if (newGen && reason.isNotEmpty()) m.data = m.data + L + reason
        m.addFlag(Flag.Refused).addFlag(Flag.Private)
        return m
    }

    fun fileInfoFrom(m: BeeMessage, protoVersion: Int): BeeFileInfo? {
        val fi = BeeFileInfo(name = m.text)
        val sl = m.data.split(Proto.LINE).toMutableList()
        if (sl.size < 4) return null
        fun take() = sl.removeAt(0)
        fi.hostPort = take().trim().toIntOrNull() ?: 0
        fi.size = take().trim().toLongOrNull() ?: 0
        fi.id = take().trim().toLongOrNull() ?: 0
        fi.password = take()
        fi.fileHash = if (sl.isNotEmpty()) take() else Crypto.simpleHash("${fi.id}-${fi.name}-${fi.size}")
        if (sl.isNotEmpty()) fi.shareFolder = take()
        if (sl.isNotEmpty()) fi.inShareBox = take() == "1"
        if (sl.isNotEmpty()) fi.chatPrivateId = take()
        if (sl.isNotEmpty()) {
            val s = take()
            if (s.isNotEmpty()) fi.lastModified = MessageCodec.parseIso(s, protoVersion >= Proto.FILE_TRANSFER_UTC_MODIFIED_DATE)
        }
        if (sl.isNotEmpty()) fi.mimeType = take()
        if (sl.isNotEmpty()) {
            val ct = take().trim().toIntOrNull()
            if (ct != null && ct in 0..1) fi.contentType = ct
        }
        if (sl.isNotEmpty()) {
            val pos = take().trim().toLongOrNull()
            fi.startingPosition = if (pos == null) 0 else minOf(pos, fi.size)
        }
        if (sl.isNotEmpty()) {
            val d = take().trim().toLongOrNull()
            fi.duration = if (d == null || d <= 0) -1 else d
        }
        return if (fi.id == 0L && fi.name.isEmpty()) null else fi
    }

    // ---- folders ---------------------------------------------------------------------

    fun folderMessage(folderName: String, files: List<BeeFileInfo>, serverPort: Int): BeeMessage {
        val items = files.map { fi ->
            listOf(fi.name, fi.suffix, fi.size.toString(), fi.id.toString(), fi.password, fi.fileHash, fi.shareFolder, fi.chatPrivateId).joinToString(L)
        }
        val m = BeeMessage(MessageType.Folder, newId(), items.joinToString(P))
        m.data = listOf(serverPort.toString(), folderName).joinToString(L)
        m.addFlag(Flag.Request).addFlag(Flag.Private)
        return m
    }

    fun folderRefused(folderName: String, chatPrivateId: String): BeeMessage {
        val m = BeeMessage(MessageType.Folder, newId(), folderName)
        m.addFlag(Flag.Refused).addFlag(Flag.Private)
        m.data = chatPrivateId
        return m
    }

    /** Returns the folder name and its files (hostAddress to be filled by the caller). */
    fun folderFrom(m: BeeMessage): Pair<String, List<BeeFileInfo>>? {
        val sl = m.data.split(Proto.LINE).toMutableList()
        if (sl.size < 2) return null
        val port = sl.removeAt(0).trim().toIntOrNull() ?: return null
        val folderName = sl.removeAt(0)
        val files = mutableListOf<BeeFileInfo>()
        for (item in m.text.split(Proto.PARAGRAPH).filter { it.isNotEmpty() }) {
            val t = item.split(Proto.LINE).toMutableList()
            if (t.size >= 7) {
                val fi = BeeFileInfo(hostPort = port)
                fi.name = t.removeAt(0)
                t.removeAt(0) // suffix (obsolete)
                fi.size = t.removeAt(0).trim().toLongOrNull() ?: 0
                fi.id = t.removeAt(0).trim().toLongOrNull() ?: 0
                fi.password = t.removeAt(0)
                fi.fileHash = t.removeAt(0)
                fi.shareFolder = t.removeAt(0).replace('\\', '/')
                if (t.isNotEmpty()) fi.chatPrivateId = t.removeAt(0)
                files += fi
            }
        }
        return folderName to files
    }

    // ---- file share list ---------------------------------------------------------------

    fun fileShareRequest() = BeeMessage(MessageType.Share, Proto.ID_SHARE_MESSAGE, "").addFlag(Flag.Request)

    fun fileShareList(files: List<BeeFileInfo>, serverPort: Int): BeeMessage {
        val items = if (serverPort > 0) files.map { fi ->
            listOf(fi.name, fi.suffix, fi.size.toString(), fi.id.toString(), fi.password, fi.fileHash, fi.shareFolder).joinToString(L)
        } else emptyList()
        val m = BeeMessage(MessageType.Share, Proto.ID_SHARE_MESSAGE, items.joinToString(P))
        m.data = if (items.isEmpty()) "0" else serverPort.toString()
        m.addFlag(Flag.List)
        return m
    }

    fun fileShareFrom(m: BeeMessage): List<BeeFileInfo> {
        val sl = m.data.split(Proto.LINE)
        val port = sl.firstOrNull()?.trim()?.toIntOrNull() ?: return emptyList()
        val out = mutableListOf<BeeFileInfo>()
        for (item in m.text.split(Proto.PARAGRAPH).filter { it.isNotEmpty() }) {
            val t = item.split(Proto.LINE).toMutableList()
            if (t.size >= 5) {
                val fi = BeeFileInfo(hostPort = port)
                fi.name = t.removeAt(0)
                t.removeAt(0)
                fi.size = t.removeAt(0).trim().toLongOrNull() ?: 0
                fi.id = t.removeAt(0).trim().toLongOrNull() ?: 0
                fi.password = t.removeAt(0)
                fi.fileHash = if (t.isNotEmpty()) t.removeAt(0) else Crypto.simpleHash("${fi.id}-${fi.name}-${fi.size}")
                if (t.isNotEmpty()) fi.shareFolder = t.removeAt(0).replace('\\', '/')
                out += fi
            }
        }
        return out
    }

    // ---- share box (BeeBOX): we do not provide one -----------------------------------------

    fun shareBoxRefuse(folderName: String, create: Boolean): BeeMessage {
        val m = BeeMessage(MessageType.ShareBox, Proto.ID_SHAREBOX_MESSAGE, "")
        m.data = listOf("0", folderName).joinToString(L)
        m.addFlag(Flag.Refused)
        m.addFlag(if (create) Flag.Create else Flag.Request)
        return m
    }

    fun shareBoxFolderName(m: BeeMessage): String = m.data.split(Proto.LINE).getOrElse(1) { "" }

    // ---- hive --------------------------------------------------------------------------

    fun hiveList(addresses: List<Pair<String, Int>>): BeeMessage {
        val m = BeeMessage(MessageType.Hive, newId(), addresses.joinToString(P) { "${it.first}$L${it.second}" })
        m.addFlag(Flag.List)
        return m
    }

    fun hiveFrom(m: BeeMessage): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        for (rec in m.text.split(Proto.PARAGRAPH).filter { it.isNotEmpty() }) {
            val t = rec.split(Proto.LINE)
            if (t.size >= 2) {
                val port = t[1].trim().toIntOrNull() ?: continue
                if (t[0].isNotEmpty() && port in 1..65535) out += t[0] to port
            }
        }
        return out
    }

    // ---- desktop sharing: we are never a source and refuse to be watched ---------------------

    fun refuseDesktop() = BeeMessage(MessageType.ShareDesktop, Proto.ID_SHAREDESKTOP_MESSAGE, "").addFlag(Flag.Refused)

    // ---- discovery beacon --------------------------------------------------------------------

    fun beep(listenerPort: Int, toAddress: String): BeeMessage {
        val m = BeeMessage(MessageType.Beep, Proto.ID_BEEP_MESSAGE, listenerPort.toString())
        m.data = toAddress
        return m
    }

    // ---- text helpers ------------------------------------------------------------------

    /** BeeBEEP sends '<' and '>' as HTML entities unless HTML tags are enabled; also strips simple markup. */
    fun escapeOutgoing(text: String): String = text.replace("<", "&lt;").replace(">", "&gt;")

    fun unescapeIncoming(text: String): String {
        var t = text
        if (t.contains('<')) {
            t = t.replace(Regex("(?i)<br\\s*/?>"), "\n")
            t = t.replace(Regex("<[^>]{1,200}>"), "")
        }
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    }

    // ---- file transfer confirmations --------------------------------------------------------

    fun bytesArrivedConfirmation(proto: Int, bytes: Long, total: Long, pause: Boolean = false): ByteArray {
        var ba: ByteArray
        if (proto < Proto.FILE_TRANSFER_RESUME) {
            ba = bytes.toString().toByteArray()
            val padded = (ba.size + 15) / 16 * 16
            val out = ByteArray(padded) { '0'.code.toByte() }
            System.arraycopy(ba, 0, out, padded - ba.size, ba.size)
            return out
        }
        val s = listOf(bytes.toString(), total.toString(), if (pause) "2" else "1", " ").joinToString(L)
        ba = s.toByteArray(Charsets.UTF_8)
        val padded = (ba.size + 15) / 16 * 16
        val out = ByteArray(padded) { '0'.code.toByte() }
        System.arraycopy(ba, 0, out, 0, ba.size)
        return out
    }

    data class Confirmation(val bytes: Long, val total: Long, val pause: Boolean)

    fun parseBytesArrived(proto: Int, data: ByteArray): Confirmation? {
        if (proto < Proto.FILE_TRANSFER_RESUME) {
            val v = String(data, Charsets.UTF_8).trim().toLongOrNull() ?: return null
            return Confirmation(v, -1, false)
        }
        val sl = String(data, Charsets.UTF_8).trim().split(Proto.LINE)
        if (sl.size < 3) return null
        val b = sl[0].trim().toLongOrNull() ?: return null
        val t = sl[1].trim().toLongOrNull() ?: -1
        val cmd = sl[2].trim().toIntOrNull()
        return Confirmation(b, t, cmd == 2)
    }
}
