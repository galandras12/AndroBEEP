package com.galandras12.droidbeep.data

import com.galandras12.droidbeep.core.Chat
import com.galandras12.droidbeep.core.ChatMessage
import com.galandras12.droidbeep.core.ChatType
import com.galandras12.droidbeep.core.Delivery
import com.galandras12.droidbeep.core.EngineStorage
import com.galandras12.droidbeep.core.FileRef
import com.galandras12.droidbeep.core.LocalFile
import com.galandras12.droidbeep.core.MsgKind
import com.galandras12.droidbeep.core.PendingMessage
import com.galandras12.droidbeep.core.Peer
import com.galandras12.droidbeep.core.TransferState
import com.galandras12.droidbeep.core.VCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Persists the engine state as small JSON files in the app's private storage. */
class JsonStorage(private val dir: File) : EngineStorage {
    private val msgDir = File(dir, "messages").also { it.mkdirs() }
    private val lock = Any()

    init { dir.mkdirs() }

    private fun read(name: String): String? = synchronized(lock) {
        val f = File(dir, name)
        if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    private fun write(f: File, text: String) = synchronized(lock) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    private fun msgFile(chatId: String) = File(msgDir, sha(chatId) + ".json")

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- peers --------------------------------------------------------------------------------

    override fun loadPeers(): List<Peer> = runCatching {
        val a = JSONArray(read("peers.json") ?: return emptyList())
        (0 until a.length()).map { peerFrom(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun savePeers(peers: List<Peer>) {
        val a = JSONArray(); peers.forEach { a.put(peerTo(it)) }
        write(File(dir, "peers.json"), a.toString())
    }

    private fun peerTo(p: Peer) = JSONObject().apply {
        put("key", p.key); put("name", p.name); put("account", p.account); put("domain", p.domain); put("hash", p.hash)
        put("status", p.status); put("statusDescription", p.statusDescription); put("color", p.color); put("version", p.version)
        put("qt", p.qtVersion); put("host", p.host); put("port", p.port); put("localHost", p.localHostName)
        put("proto", p.protocolVersion); put("favorite", p.favorite); put("helper", p.isHelper)
        put("last", p.lastConnection?.toString() ?: "")
        put("vc", vcardTo(p.vcard))
    }

    private fun peerFrom(o: JSONObject) = Peer(
        key = o.getString("key"), name = o.optString("name"), account = o.optString("account"), domain = o.optString("domain"),
        hash = o.optString("hash"), status = 0, statusDescription = o.optString("statusDescription"), color = o.optString("color", "#757575"),
        version = o.optString("version"), qtVersion = o.optString("qt"), host = o.optString("host"), port = o.optInt("port", 6475),
        localHostName = o.optString("localHost"), protocolVersion = o.optInt("proto"), online = false, favorite = o.optBoolean("favorite"),
        lastConnection = o.optString("last").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
        isHelper = o.optBoolean("helper"), vcard = o.optJSONObject("vc")?.let { vcardFrom(it) } ?: VCard(),
    )

    private fun vcardTo(v: VCard) = JSONObject().apply {
        put("nick", v.nickName); put("first", v.firstName); put("last", v.lastName); put("birthday", v.birthday)
        put("email", v.email); put("phone", v.phone); put("room", v.roomLocation); put("info", v.info); put("photo", v.photoPngBase64)
    }

    private fun vcardFrom(o: JSONObject) = VCard(
        o.optString("nick"), o.optString("first"), o.optString("last"), o.optString("birthday"), o.optString("email"),
        o.optString("phone"), o.optString("room"), o.optString("info"), o.optString("photo"),
    )

    // ---- chats --------------------------------------------------------------------------------

    override fun loadChats(): List<Chat> = runCatching {
        val a = JSONArray(read("chats.json") ?: return emptyList())
        (0 until a.length()).map { chatFrom(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun saveChats(chats: List<Chat>) {
        val a = JSONArray(); chats.forEach { a.put(chatTo(it)) }
        write(File(dir, "chats.json"), a.toString())
    }

    private fun chatTo(c: Chat) = JSONObject().apply {
        put("id", c.id); put("type", c.type.name); put("name", c.name); put("privateId", c.privateId)
        put("members", JSONArray(c.members.toList())); put("modified", c.lastModified?.toString() ?: "")
        put("unread", c.unread); put("activity", c.lastActivity.toString()); put("preview", c.lastPreview)
        put("member", c.localUserIsMember)
    }

    private fun chatFrom(o: JSONObject): Chat {
        val m = o.optJSONArray("members") ?: JSONArray()
        return Chat(
            id = o.getString("id"), type = ChatType.valueOf(o.getString("type")), name = o.optString("name"), privateId = o.optString("privateId"),
            members = (0 until m.length()).map { m.getString(it) }.toSet(),
            lastModified = o.optString("modified").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            unread = o.optInt("unread"), lastActivity = runCatching { Instant.parse(o.optString("activity")) }.getOrDefault(Instant.EPOCH),
            lastPreview = o.optString("preview"), localUserIsMember = o.optBoolean("member", true),
        )
    }

    // ---- messages -----------------------------------------------------------------------------

    override fun loadMessages(chatId: String): List<ChatMessage> = runCatching {
        val f = msgFile(chatId)
        if (!f.exists()) return emptyList()
        val a = JSONArray(synchronized(lock) { f.readText() })
        (0 until a.length()).map { msgFrom(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun saveMessages(chatId: String, messages: List<ChatMessage>) {
        val a = JSONArray(); messages.forEach { a.put(msgTo(it)) }
        write(msgFile(chatId), a.toString())
    }

    override fun deleteMessages(chatId: String) {
        synchronized(lock) { msgFile(chatId).delete() }
    }

    private fun msgTo(m: ChatMessage) = JSONObject().apply {
        put("id", m.localId); put("chat", m.chatId); put("from", m.senderKey ?: JSONObject.NULL); put("text", m.text)
        put("time", m.time.toString()); put("kind", m.kind.name); put("delivery", m.delivery.name); put("wire", m.wireId)
        put("important", m.important); put("source", m.sourceCode); put("auto", m.auto)
        m.file?.let { f ->
            put("file", JSONObject().apply {
                put("id", f.fileId); put("name", f.name); put("size", f.size); put("state", f.state.name); put("up", f.upload)
                put("done", f.transferred); put("path", f.localPath ?: JSONObject.NULL); put("mime", f.mimeType)
                put("dur", f.durationMs); put("err", f.error); put("folder", f.folderName); put("count", f.fileCount)
            })
        }
    }

    private fun msgFrom(o: JSONObject): ChatMessage {
        val f = o.optJSONObject("file")?.let { fo ->
            var st = runCatching { TransferState.valueOf(fo.getString("state")) }.getOrDefault(TransferState.ERROR)
            // transfers that were running when the app stopped can not continue
            if (st == TransferState.TRANSFERRING || st == TransferState.CONNECTING || st == TransferState.QUEUED) st = TransferState.ERROR
            if (st == TransferState.OFFERED) st = TransferState.CANCELED
            FileRef(
                fo.optLong("id"), fo.optString("name"), fo.optLong("size"), st, fo.optBoolean("up"), fo.optLong("done"),
                if (fo.isNull("path")) null else fo.optString("path"), fo.optString("mime"), fo.optLong("dur", -1),
                fo.optString("err"), fo.optString("folder"), fo.optInt("count", 1),
            )
        }
        return ChatMessage(
            localId = o.getLong("id"), chatId = o.getString("chat"), senderKey = if (o.isNull("from")) null else o.getString("from"),
            text = o.optString("text"), time = runCatching { Instant.parse(o.optString("time")) }.getOrDefault(Instant.now()),
            kind = runCatching { MsgKind.valueOf(o.optString("kind")) }.getOrDefault(MsgKind.TEXT),
            delivery = runCatching { Delivery.valueOf(o.optString("delivery")) }.getOrDefault(Delivery.NONE),
            wireId = o.optLong("wire"), important = o.optBoolean("important"), sourceCode = o.optBoolean("source"),
            auto = o.optBoolean("auto"), file = f,
        )
    }

    // ---- pending, refused, shares ----------------------------------------------------------------

    override fun loadPending(): List<PendingMessage> = runCatching {
        val a = JSONArray(read("pending.json") ?: return emptyList())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PendingMessage(
                o.getString("peer"), o.getString("chat"), o.optLong("lid"), o.getString("type"), o.optLong("wire"), o.optString("text"),
                o.optString("data"), o.optInt("flags"), o.optLong("time"), if (o.isNull("voice")) null else o.optString("voice"), o.optLong("dur", -1),
            )
        }
    }.getOrDefault(emptyList())

    override fun savePending(list: List<PendingMessage>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().apply {
            put("peer", p.peerKey); put("chat", p.chatId); put("lid", p.localMessageId); put("type", p.type); put("wire", p.wireId)
            put("text", p.text); put("data", p.data); put("flags", p.flags); put("time", p.timeMillis)
            put("voice", p.voicePath ?: JSONObject.NULL); put("dur", p.voiceDurationMs)
        })
        write(File(dir, "pending.json"), a.toString())
    }

    override fun loadRefusedChats(): Set<String> = runCatching {
        val a = JSONArray(read("refused.json") ?: return emptySet())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    override fun saveRefusedChats(ids: Set<String>) = write(File(dir, "refused.json"), JSONArray(ids.toList()).toString())

    override fun loadSharedFiles(): List<LocalFile> = runCatching {
        val a = JSONArray(read("shares.json") ?: return emptyList())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LocalFile(o.getString("uri"), o.getString("name"), o.optLong("size"), o.optLong("mod"), o.optString("mime"), o.optString("folder"))
        }
    }.getOrDefault(emptyList())

    override fun saveSharedFiles(files: List<LocalFile>) {
        val a = JSONArray()
        for (f in files) a.put(JSONObject().apply {
            put("uri", f.uri); put("name", f.name); put("size", f.size); put("mod", f.lastModified); put("mime", f.mimeType); put("folder", f.shareFolder)
        })
        write(File(dir, "shares.json"), a.toString())
    }
}
