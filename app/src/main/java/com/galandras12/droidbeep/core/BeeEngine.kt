package com.galandras12.droidbeep.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The BeeBEEP node: listens for peers, discovers them with UDP broadcast/multicast, keeps the
 * conversations (default chat, private chats, group chats) and handles file transfers.
 * Pure Kotlin/JVM: everything platform specific goes through [EngineHost].
 */
class BeeEngine(internal val host: EngineHost, initialConfig: EngineConfig) {

    @Volatile var config: EngineConfig = initialConfig
        private set

    // ---- observable state ---------------------------------------------------------------------

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** "chatId|peerKey" -> instant (ms) until which the peer is shown as typing. */
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow(UserStatus.Online)
    val localStatus: StateFlow<Int> = _status.asStateFlow()
    private val _statusDescription = MutableStateFlow("")
    val localStatusDescription: StateFlow<String> = _statusDescription.asStateFlow()

    private val _vcard = MutableStateFlow(VCard())
    val localVCard: StateFlow<VCard> = _vcard.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()

    internal val transfers = Transfers(this)
    val transferList: StateFlow<List<TransferInfo>> get() = transfers.list
    val remoteShares: StateFlow<List<SharedFile>> get() = transfers.remoteShares
    val localShares: StateFlow<List<LocalFile>> get() = transfers.localShares

    fun messages(chatId: String): Flow<List<ChatMessage>> =
        _messages.map { it[chatId] ?: emptyList() }.distinctUntilChanged()

    // ---- internals ----------------------------------------------------------------------------

    internal val stateLock = Any()
    private val peerMap = LinkedHashMap<String, Peer>()
    private val chatMap = LinkedHashMap<String, Chat>()
    private val messageMap = HashMap<String, MutableList<ChatMessage>>()
    private val refusedChats = HashSet<String>()
    private var pending = ArrayList<PendingMessage>()
    private val wireToLocal = LinkedHashMap<Long, Pair<String, Long>>() // wire id -> (chatId, local id)
    private val localIds = AtomicLong(System.currentTimeMillis())

    internal inner class Session(val key: String, val link: BeeLink) {
        @Volatile var closed = false
        @Volatile var lastPing = 0L
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val lastDial = ConcurrentHashMap<String, Long>()

    private var pool = Executors.newCachedThreadPool { r -> Thread(r, "bee-io").also { it.isDaemon = true } }
    private var scheduler: ScheduledExecutorService? = null
    private var listener: ServerSocket? = null
    private var discovery: Discovery? = null
    private var passwordHash: ByteArray = Crypto.passwordHash(initialConfig.networkPassword)
    private var openChatId: String? = null
    private var saveScheduled = false
    private var ticks = 0L

    internal val passwordHashBytes: ByteArray get() = passwordHash

    val localKey: String get() = peerKey(config.accountName, config.domainName, config.nickname)

    fun peerKey(account: String, domain: String, name: String = ""): String =
        (account.ifEmpty { name }).lowercase() + "@" + domain.lowercase()

    internal fun identity(): LocalIdentity = LocalIdentity(
        nickname = config.nickname,
        accountName = config.accountName,
        domainName = config.domainName,
        localHostName = config.localHostName,
        hash = config.userHash,
        color = config.color,
        status = _status.value,
        statusDescription = _statusDescription.value,
        statusChangedIn = statusChangedIn,
        workgroups = config.workgroups,
        listenerPort = listener?.localPort ?: config.listenerPort,
    )

    private var statusChangedIn: Instant? = null

    internal fun log(msg: String) {
        host.log(msg)
        synchronized(stateLock) {
            val l = _log.value
            _log.value = (if (l.size > 300) l.drop(l.size - 300) else l) + msg
        }
    }

    // ==== lifecycle ===========================================================================

    fun start() {
        if (_running.value) return
        pool = Executors.newCachedThreadPool { r -> Thread(r, "bee-io").also { it.isDaemon = true } }
        loadState()
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            try {
                ss.bind(InetSocketAddress(config.listenerPort))
            } catch (e: IOException) {
                log("Port ${config.listenerPort} is busy, using a free one")
                ss.bind(InetSocketAddress(0))
            }
            listener = ss
        } catch (e: IOException) {
            log("Cannot open the listener: ${e.message}")
            return
        }
        _running.value = true
        transfers.start(config.fileTransferPort)
        host.setMulticastLock(true)
        discovery = Discovery({ listener?.localPort ?: config.listenerPort }, config.broadcastPort, config.useMulticast,
            { h, p -> onBeacon(h, p) }, { log(it) }).also { it.start() }
        pool.execute { acceptLoop() }
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bee-tick").also { it.isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ safe { tick() } }, 1, 1, TimeUnit.SECONDS)
        }
        pool.execute { safe { searchPeers() } }
        log("BeeBeep Droid started: listener ${listener?.localPort}, file transfer ${transfers.serverPort}")
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        scheduler?.shutdownNow(); scheduler = null
        discovery?.stop(); discovery = null
        try { listener?.close() } catch (_: IOException) {}
        listener = null
        transfers.stop()
        for (s in sessions.values.toList()) closeSession(s, notify = true)
        host.setMulticastLock(false)
        saveNow()
        pool.shutdownNow()
        log("BeeBeep Droid stopped")
    }

    fun restart(newConfig: EngineConfig) {
        val wasRunning = _running.value
        if (wasRunning) stop()
        config = newConfig
        passwordHash = Crypto.passwordHash(newConfig.networkPassword)
        if (wasRunning) start()
    }

    /** Applies settings that do not need the sockets to be reopened. */
    fun updateConfigLive(newConfig: EngineConfig) {
        config = newConfig
        passwordHash = Crypto.passwordHash(newConfig.networkPassword)
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { log("Error: ${e.javaClass.simpleName}: ${e.message}") }
    }

    // ==== persistence =========================================================================

    private fun loadState() {
        synchronized(stateLock) {
            peerMap.clear(); chatMap.clear(); messageMap.clear()
            val st = host.storage
            for (p in st.loadPeers()) peerMap[p.key] = p.copy(online = false, status = UserStatus.Offline)
            for (c in st.loadChats()) chatMap[c.id] = c
            refusedChats.clear(); refusedChats.addAll(st.loadRefusedChats())
            pending = ArrayList(st.loadPending())
            ensureDefaultChat()
            for (c in chatMap.values.toList()) {
                val list = st.loadMessages(c.id).toMutableList()
                messageMap[c.id] = list
            }
            for (p in peerMap.values) if (p.key != localKey) ensurePrivateChatLocked(p)
            publishAllLocked()
        }
        transfers.loadShares()
    }

    private fun publishAllLocked() {
        _peers.value = peerMap.values.sortedWith(compareBy<Peer>({ !it.online }, { it.displayName.lowercase() }))
        _chats.value = sortedChatsLocked()
        _messages.value = messageMap.mapValues { it.value.toList() }
    }

    private fun sortedChatsLocked(): List<Chat> =
        chatMap.values.sortedWith(compareByDescending<Chat> { it.type == ChatType.DEFAULT }.thenByDescending { it.lastActivity })

    private fun publishPeersLocked() {
        _peers.value = peerMap.values.sortedWith(compareBy<Peer>({ !it.online }, { it.displayName.lowercase() }))
    }

    private fun publishChatsLocked() {
        _chats.value = sortedChatsLocked()
    }

    private fun publishMessagesLocked(chatId: String) {
        _messages.value = _messages.value + (chatId to (messageMap[chatId]?.toList() ?: emptyList()))
    }

    internal fun scheduleSave() {
        synchronized(stateLock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        try {
            scheduler?.schedule({ saveNow() }, 3, TimeUnit.SECONDS) ?: saveNow()
        } catch (e: Exception) {
            saveNow()
        }
    }

    fun saveNow() {
        val st = host.storage
        val peersCopy: List<Peer>
        val chatsCopy: List<Chat>
        val msgs: Map<String, List<ChatMessage>>
        val pend: List<PendingMessage>
        val refused: Set<String>
        synchronized(stateLock) {
            saveScheduled = false
            peersCopy = peerMap.values.toList()
            chatsCopy = chatMap.values.toList()
            msgs = messageMap.mapValues { it.value.takeLast(MAX_STORED_MESSAGES) }
            pend = pending.toList()
            refused = refusedChats.toSet()
        }
        try {
            st.savePeers(peersCopy)
            st.saveChats(chatsCopy)
            for ((id, l) in msgs) st.saveMessages(id, l)
            st.savePending(pend)
            st.saveRefusedChats(refused)
        } catch (e: Exception) {
            log("Save error: ${e.message}")
        }
    }

    // ==== chats ===============================================================================

    private fun ensureDefaultChat() {
        if (!chatMap.containsKey(DEFAULT_CHAT_ID)) {
            chatMap[DEFAULT_CHAT_ID] = Chat(DEFAULT_CHAT_ID, ChatType.DEFAULT, Proto.DEFAULT_CHAT_NAME, Proto.DEFAULT_CHAT_PRIVATE_ID)
            messageMap.getOrPut(DEFAULT_CHAT_ID) { ArrayList() }
        }
    }

    private fun ensurePrivateChatLocked(p: Peer): Chat {
        val id = privateChatId(p.key)
        return chatMap.getOrPut(id) {
            messageMap.getOrPut(id) { ArrayList() }
            Chat(id, ChatType.PRIVATE, p.displayName, "", setOf(p.key))
        }.also { chat ->
            if (chat.name != p.displayName) chatMap[id] = chat.copy(name = p.displayName)
        }
    }

    fun chat(id: String): Chat? = synchronized(stateLock) { chatMap[id] }
    fun peer(key: String): Peer? = synchronized(stateLock) { peerMap[key] }
    fun privateChatId(peerKey: String) = "u:$peerKey"
    fun openPrivateChat(peerKey: String): String = synchronized(stateLock) {
        val p = peerMap[peerKey] ?: return DEFAULT_CHAT_ID
        val c = ensurePrivateChatLocked(p)
        publishChatsLocked()
        c.id
    }

    fun setOpenChat(chatId: String?) {
        openChatId = chatId
        if (chatId != null) markChatRead(chatId)
    }

    fun markChatRead(chatId: String) {
        val c: Chat
        val hadUnread: Boolean
        synchronized(stateLock) {
            c = chatMap[chatId] ?: return
            hadUnread = c.unread > 0
            if (hadUnread) {
                chatMap[chatId] = c.copy(unread = 0)
                publishChatsLocked()
            }
        }
        if (hadUnread) {
            val m = Msgs.chatRead(c)
            for (key in recipientsOf(c)) {
                val s = sessions[key] ?: continue
                if (s.link.protoVersion > 62) s.link.send(m)
            }
        }
    }

    private fun recipientsOf(c: Chat): Set<String> = when (c.type) {
        ChatType.DEFAULT -> synchronized(stateLock) { peerMap.values.filter { it.online }.map { it.key }.toSet() }
        else -> c.members
    }

    private fun nextLocalId() = localIds.incrementAndGet()

    internal fun addMessageLocked(chatId: String, msg: ChatMessage, countUnread: Boolean): ChatMessage {
        val list = messageMap.getOrPut(chatId) { ArrayList() }
        list.add(msg)
        if (list.size > MAX_STORED_MESSAGES + 200) {
            repeat(list.size - MAX_STORED_MESSAGES) { list.removeAt(0) }
        }
        val c = chatMap[chatId]
        if (c != null) {
            val preview = when {
                msg.kind == MsgKind.FILE -> "📎 " + (msg.file?.name ?: msg.text)
                msg.kind == MsgKind.VOICE -> "🎤 " + msg.text
                else -> msg.text
            }
            chatMap[chatId] = c.copy(
                lastActivity = msg.time,
                lastPreview = preview.take(120),
                unread = if (countUnread && openChatId != chatId) c.unread + 1 else c.unread
            )
        }
        publishMessagesLocked(chatId)
        publishChatsLocked()
        scheduleSave()
        return msg
    }

    internal fun systemMessage(chatId: String, text: String, kind: MsgKind = MsgKind.SYSTEM) {
        synchronized(stateLock) {
            if (!chatMap.containsKey(chatId)) return
            addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, text, Instant.now(), kind), false)
        }
    }

    internal fun systemMessageToChatsWith(peerKey: String, text: String) {
        synchronized(stateLock) {
            for (c in chatMap.values.toList()) {
                if (c.type == ChatType.DEFAULT || c.members.contains(peerKey)) {
                    addMessageLocked(c.id, ChatMessage(nextLocalId(), c.id, null, text, Instant.now(), MsgKind.SYSTEM), false)
                }
            }
        }
    }

    internal fun updateMessage(chatId: String, localId: Long, transform: (ChatMessage) -> ChatMessage) {
        synchronized(stateLock) {
            val list = messageMap[chatId] ?: return
            val i = list.indexOfFirst { it.localId == localId }
            if (i < 0) return
            list[i] = transform(list[i])
            publishMessagesLocked(chatId)
        }
        scheduleSave()
    }

    internal fun addFileMessage(chatId: String, senderKey: String?, text: String, file: FileRef, kind: MsgKind, countUnread: Boolean): ChatMessage =
        synchronized(stateLock) {
            addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, senderKey, text, Instant.now(), kind, file = file), countUnread)
        }

    // ---- sending ---------------------------------------------------------------------------

    /** Sends a text message to a chat; returns false if nothing could be sent or queued. */
    fun sendMessage(chatId: String, text: String, important: Boolean = false, sourceCode: Boolean = false): Boolean {
        val c = chat(chatId) ?: return false
        val body = text.trim('\n', '\r').trimEnd()
        if (body.isBlank()) return false
        if (!_running.value) {
            systemMessage(chatId, "Not connected: the message cannot be sent.")
            return false
        }
        if (c.type == ChatType.GROUP && !c.localUserIsMember) return false
        val m = Msgs.chat(c, if (sourceCode) body else Msgs.escapeOutgoing(body))
        if (important) m.addFlag(Flag.Important)
        if (sourceCode) m.addFlag(Flag.SourceCode)

        val localMsg: ChatMessage
        synchronized(stateLock) {
            localMsg = ChatMessage(nextLocalId(), chatId, null, body, Instant.now(), MsgKind.TEXT, Delivery.NONE, m.id, important, sourceCode)
            addMessageLocked(chatId, localMsg, false)
            wireToLocal[m.id] = chatId to localMsg.localId
            if (wireToLocal.size > 1000) wireToLocal.remove(wireToLocal.keys.first())
        }

        var sent = 0
        var queued = 0
        if (c.type == ChatType.DEFAULT) {
            for (s in sessions.values.toList()) if (s.link.send(m)) sent++
        } else {
            for (key in c.members) {
                val s = sessions[key]
                if (s != null && s.link.send(m)) sent++
                else {
                    queueMessage(key, chatId, localMsg.localId, m)
                    queued++
                }
            }
        }
        val state = when {
            sent > 0 -> Delivery.SENT
            queued > 0 -> Delivery.QUEUED
            else -> Delivery.FAILED
        }
        updateMessage(chatId, localMsg.localId) { it.copy(delivery = state) }
        if (sent == 0 && queued == 0) systemMessage(chatId, "Nobody has received the message.")
        else if (queued > 0 && c.type != ChatType.DEFAULT) {
            val names = c.members.filter { sessions[it] == null }.mapNotNull { peer(it)?.displayName }
            if (names.isNotEmpty()) systemMessage(chatId, "The message will be delivered to ${names.joinToString(", ")}.")
        }
        return sent > 0 || queued > 0
    }

    private fun queueMessage(peerKey: String, chatId: String, localId: Long, m: BeeMessage) {
        synchronized(stateLock) {
            pending.add(PendingMessage(peerKey, chatId, localId, m.type.name, m.id, m.text, m.data, m.flags, m.timestamp.toEpochMilli()))
        }
        scheduleSave()
    }

    internal fun queueVoice(peerKey: String, chatId: String, localId: Long, path: String, durationMs: Long) {
        synchronized(stateLock) {
            pending.add(PendingMessage(peerKey, chatId, localId, MessageType.File.name, 0, "", "", 0, System.currentTimeMillis(), path, durationMs))
        }
        scheduleSave()
    }

    fun pendingCount(chatId: String): Int = synchronized(stateLock) { pending.count { it.chatId == chatId } }

    fun discardPending(chatId: String) {
        synchronized(stateLock) { pending.removeAll { it.chatId == chatId } }
        scheduleSave()
    }

    private fun flushPending(peerKey: String) {
        val s = sessions[peerKey] ?: return
        val mine: List<PendingMessage>
        synchronized(stateLock) {
            mine = pending.filter { it.peerKey == peerKey }
            if (mine.isEmpty()) return
            pending.removeAll(mine.toSet())
        }
        val failed = ArrayList<PendingMessage>()
        var delivered = 0
        for (p in mine) {
            if (p.voicePath != null) {
                if (transfers.sendVoiceTo(peerKey, p.chatId, p.voicePath, p.voiceDurationMs, existingLocalId = p.localMessageId)) delivered++ else failed.add(p)
                continue
            }
            val m = BeeMessage(MessageType.valueOf(p.type), p.wireId, p.text, p.data, p.flags, Instant.ofEpochMilli(p.timeMillis))
            m.addFlag(Flag.Delayed)
            if (s.link.send(m)) {
                delivered++
                updateMessage(p.chatId, p.localMessageId) { it.copy(delivery = if (it.delivery == Delivery.QUEUED) Delivery.SENT else it.delivery) }
            } else failed.add(p)
        }
        if (failed.isNotEmpty()) synchronized(stateLock) { pending.addAll(failed) }
        if (delivered > 0) {
            val name = peer(peerKey)?.displayName ?: peerKey
            synchronized(stateLock) {
                for (chatId in mine.map { it.chatId }.toSet()) {
                    if (chatMap.containsKey(chatId)) addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "Offline messages sent to $name.", Instant.now(), MsgKind.SYSTEM), false)
                }
            }
        }
        scheduleSave()
    }

    fun sendTyping(chatId: String) {
        val c = chat(chatId) ?: return
        val m = Msgs.writing(if (c.type == ChatType.PRIVATE) "" else c.privateId)
        for (key in recipientsOf(c)) sessions[key]?.link?.send(m)
    }

    fun sendBuzz(peerKey: String): Boolean {
        val s = sessions[peerKey] ?: return false
        val ok = s.link.send(Msgs.buzz())
        if (ok) systemMessage(privateChatId(peerKey), "You have buzzed ${peer(peerKey)?.displayName}.")
        return ok
    }

    fun sendHelpRequest(peerKey: String, text: String = "Help!"): Boolean {
        val s = sessions[peerKey] ?: return false
        return s.link.send(Msgs.helpRequest(text)).also {
            if (it) systemMessage(privateChatId(peerKey), "You have asked ${peer(peerKey)?.displayName} for help.", MsgKind.HELP)
        }
    }

    // ==== user data ===========================================================================

    fun setStatus(status: Int, description: String) {
        _status.value = status
        _statusDescription.value = description
        statusChangedIn = Instant.now()
        val m = Msgs.userStatus(status, description)
        for (s in sessions.values) s.link.send(m)
    }

    fun setVCard(vc: VCard) {
        _vcard.value = vc
        sendVCardToAll()
    }

    private fun sendVCardToAll() {
        for (s in sessions.values) sendVCard(s)
    }

    private fun sendVCard(s: Session) {
        val vc = _vcard.value
        if (vc.hasOnlyNickName && !config.acceptHelpRequests) return
        if (s.link.protoVersion > 1) s.link.send(Msgs.vcard(vc.copy(nickName = vc.nickName.ifEmpty { config.nickname }), config.color, s.link.protoVersion, config.acceptHelpRequests))
    }

    fun toggleFavorite(peerKey: String) {
        synchronized(stateLock) {
            val p = peerMap[peerKey] ?: return
            peerMap[peerKey] = p.copy(favorite = !p.favorite)
            publishPeersLocked()
        }
        scheduleSave()
    }

    fun removeOfflinePeer(peerKey: String): Boolean {
        synchronized(stateLock) {
            val p = peerMap[peerKey] ?: return true
            if (p.online) return false
            if (chatMap.values.any { it.type == ChatType.GROUP && it.members.contains(peerKey) }) return false
            peerMap.remove(peerKey)
            val cid = privateChatId(peerKey)
            chatMap.remove(cid); messageMap.remove(cid)
            host.storage.deleteMessages(cid)
            pending.removeAll { it.peerKey == peerKey }
            publishAllLocked()
        }
        scheduleSave()
        return true
    }

    /** Remembers an address the user typed so that the peer can be contacted even without broadcast. */
    fun connectToAddress(hostOrIp: String, port: Int = Proto.DEFAULT_LISTENER_PORT) {
        pool.execute { safe { dial(hostOrIp, port, force = true) } }
    }

    // ==== groups ==============================================================================

    fun createGroup(name: String, memberKeys: Set<String>): String? {
        val members = memberKeys.filter { it != localKey }.toSet()
        if (name.isBlank() || members.isEmpty()) return null
        val privateId = Crypto.simpleHash("${Msgs.newId()}${System.nanoTime()}${config.userHash}${java.util.UUID.randomUUID()}")
        val chatId = groupChatId(privateId)
        synchronized(stateLock) {
            chatMap[chatId] = Chat(chatId, ChatType.GROUP, name.trim(), privateId, members, Instant.now())
            messageMap[chatId] = ArrayList()
            val names = members.mapNotNull { peerMap[it]?.displayName }
            addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "You have created group chat ${name.trim()}.", Instant.now(), MsgKind.SYSTEM), false)
            addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "Chat with ${names.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
            publishChatsLocked()
        }
        sendGroupRequests(chatId)
        return chatId
    }

    fun groupChatId(privateId: String) = "g:$privateId"

    private fun sendGroupRequests(chatId: String, onlyTo: String? = null) {
        val c = chat(chatId) ?: return
        val memberPeers = synchronized(stateLock) { c.members.mapNotNull { peerMap[it] } }
        for (p in memberPeers) {
            if (onlyTo != null && p.key != onlyTo) continue
            val s = sessions[p.key] ?: continue
            s.link.send(Msgs.groupRequest(c, p, memberPeers, s.link.protoVersion))
        }
    }

    fun editGroup(chatId: String, newName: String, newMembers: Set<String>) {
        val c = chat(chatId) ?: return
        if (c.type != ChatType.GROUP) return
        val members = newMembers.filter { it != localKey }.toSet()
        if (members.isEmpty()) return
        val removed = c.members - members
        val added = members - c.members
        var changed = false
        synchronized(stateLock) {
            var nc = c
            if (newName.isNotBlank() && newName.trim() != c.name) {
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "You have changed the group name from ${c.name} to ${newName.trim()}.", Instant.now(), MsgKind.SYSTEM), false)
                nc = nc.copy(name = newName.trim()); changed = true
            }
            if (removed.isNotEmpty()) {
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "You have removed members: ${removed.mapNotNull { peerMap[it]?.displayName }.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
                pending.removeAll { it.chatId == chatId && removed.contains(it.peerKey) }
                changed = true
            }
            if (added.isNotEmpty()) {
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "You have added members: ${added.mapNotNull { peerMap[it]?.displayName }.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
                changed = true
            }
            if (changed) {
                nc = nc.copy(members = members, lastModified = Instant.now())
                chatMap[chatId] = nc
                publishChatsLocked()
            }
        }
        if (!changed) return
        sendGroupRequests(chatId)
        val nc = chat(chatId) ?: return
        val rm = Msgs.groupRemoveUser(nc)
        for (k in removed) sessions[k]?.link?.send(rm)
    }

    /** Leave a group (informing the other members) or delete a chat history. */
    fun leaveGroup(chatId: String) {
        val c = chat(chatId) ?: return
        if (c.type != ChatType.GROUP) return
        val refuse = Msgs.groupRefuse(c.privateId, c.name)
        for (k in c.members) sessions[k]?.link?.send(refuse)
        synchronized(stateLock) {
            refusedChats.add(c.privateId)
            chatMap.remove(chatId); messageMap.remove(chatId)
            host.storage.deleteMessages(chatId)
            pending.removeAll { it.chatId == chatId }
            publishAllLocked()
        }
        scheduleSave()
    }

    fun clearChat(chatId: String) {
        synchronized(stateLock) {
            messageMap[chatId]?.clear()
            val c = chatMap[chatId]
            if (c != null) chatMap[chatId] = c.copy(lastPreview = "", unread = 0)
            host.storage.deleteMessages(chatId)
            publishMessagesLocked(chatId)
            publishChatsLocked()
        }
        scheduleSave()
    }

    // ==== networking ==========================================================================

    private fun acceptLoop() {
        val ss = listener ?: return
        while (_running.value) {
            try {
                val sock = ss.accept()
                pool.execute { safe { handleIncoming(sock) } }
            } catch (e: IOException) {
                if (_running.value) {
                    log("Accept error: ${e.message}")
                    try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                } else return
            }
        }
    }

    private fun handleIncoming(sock: Socket) {
        sock.tcpNoDelay = true
        sock.keepAlive = true
        val link = BeeLink(sock, { identity() }, { passwordHash }, initiator = false)
        try {
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            link.handshake()
            sock.soTimeout = 0
            registerSession(link)
        } catch (e: Exception) {
            log("Incoming connection from ${sock.inetAddress?.hostAddress} rejected: ${e.message}")
            link.close()
        }
    }

    private fun isLocalAddress(h: String) = h == "127.0.0.1" || h == "::1" || h == "localhost" || Discovery.localAddresses().contains(h)

    private fun onBeacon(h: String, port: Int) {
        if (!_running.value) return
        val own = listener?.localPort ?: config.listenerPort
        if (isLocalAddress(h) && port == own) return
        // small random delay reduces the chance that both sides dial each other at the same time
        val delay = (50..900).random().toLong()
        try {
            scheduler?.schedule({ safe { dial(h, port, force = false) } }, delay, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun alreadyConnected(h: String): Boolean = sessions.values.any { it.link.peerHostAddress == h }

    private fun dial(h: String, port: Int, force: Boolean) {
        if (!_running.value) return
        val own = listener?.localPort ?: config.listenerPort
        if (isLocalAddress(h) && port == own) return
        val addr = "$h:$port"
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastDial[addr] ?: 0
            if (now - last < 4000) return
            if (sessions.values.any { it.link.peerHostAddress == h && it.link.peer?.listenerPort == port }) return
        }
        if (!connecting.add(addr)) return
        lastDial[addr] = now
        var link: BeeLink? = null
        try {
            link = BeeLink.connect(h, port, CONNECT_TIMEOUT_MS, { identity() }, { passwordHash })
            link.socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            link.handshake()
            link.socket.soTimeout = 0
            registerSession(link)
        } catch (e: Exception) {
            if (force) log("Cannot connect to $addr: ${e.message}")
            link?.close()
        } finally {
            connecting.remove(addr)
        }
    }

    private fun registerSession(link: BeeLink) {
        val hello = link.peer ?: run { link.close(); return }
        val key = peerKey(hello.accountName, hello.domainName, hello.name)
        if (key == localKey) {
            log("Connection from ourselves ignored")
            link.close(); return
        }
        val session = Session(key, link)
        val previous = sessions.putIfAbsent(key, session)
        if (previous != null && !previous.closed) {
            // already connected to this user: keep the first connection
            link.close(); return
        }
        if (previous != null) sessions[key] = session

        val peer: Peer
        val newPeer: Boolean
        synchronized(stateLock) {
            val old = peerMap[key]
            newPeer = old == null
            val color = if (hello.color.isEmpty() || hello.color == "#000000") (old?.color ?: "#757575") else hello.color
            peer = (old ?: Peer(key, hello.name, hello.accountName, hello.domainName)).copy(
                name = hello.name,
                account = hello.accountName,
                domain = hello.domainName,
                hash = hello.hash,
                status = hello.status,
                statusDescription = hello.statusDescription,
                color = color,
                version = hello.version,
                qtVersion = hello.qtVersion,
                host = link.peerHostAddress,
                port = hello.listenerPort,
                localHostName = hello.localHostName,
                protocolVersion = link.protoVersion,
                online = true,
                lastConnection = Instant.now(),
                encrypted = link.encrypted,
            )
            peerMap[key] = peer
            val pc = ensurePrivateChatLocked(peer)
            val dc = chatMap[DEFAULT_CHAT_ID]!!
            chatMap[DEFAULT_CHAT_ID] = dc.copy(members = dc.members + key)
            for (c in chatMap.values.toList()) if (c.type == ChatType.GROUP && c.members.contains(key)) {
                // keep group chats consistent with the (possibly renamed) member
            }
            publishAllLocked()
            if (pc.name != peer.displayName) chatMap[pc.id] = pc.copy(name = peer.displayName)
            publishChatsLocked()
        }
        log("${peer.displayName} (${link.peerHostAddress}:${hello.listenerPort}) connected, protocol ${link.protoVersion}, encrypted=${link.encrypted}")
        host.emit(EngineEvent.PeerConnected(peer))
        if (newPeer || true) systemMessageToChatsWith(key, "${peer.displayName} is online.")
        scheduleSave()

        // greetings, as BeeBEEP does after authentication
        sendVCard(session)
        pool.execute {
            safe {
                // group requests for groups this peer belongs to, offline messages, hive
                val groups = synchronized(stateLock) { chatMap.values.filter { it.type == ChatType.GROUP && it.members.contains(key) } }
                for (g in groups) sendGroupRequests(g.id, onlyTo = key)
                flushPending(key)
                if (link.protoVersion >= Proto.HIVE) sendHive(session)
            }
        }
        pool.execute { readLoop(session) }
    }

    private fun sendHive(target: Session) {
        val list = sessions.values.filter { it.key != target.key }.mapNotNull { s ->
            val p = peer(s.key) ?: return@mapNotNull null
            if (p.host.isEmpty()) null else p.host to p.port
        }
        if (list.isNotEmpty()) target.link.send(Msgs.hiveList(list))
    }

    private fun readLoop(s: Session) {
        try {
            while (_running.value && !s.closed) {
                val m = s.link.receive()
                if (!m.isValid) continue
                when (m.type) {
                    MessageType.Ping -> s.link.sendPong()
                    MessageType.Pong -> {}
                    else -> safe { handle(s, m) }
                }
            }
        } catch (e: Exception) {
            if (!s.closed) log("Connection with ${peer(s.key)?.displayName} closed: ${e.message}")
        } finally {
            closeSession(s, notify = true)
        }
    }

    private fun closeSession(s: Session, notify: Boolean) {
        if (s.closed) return
        s.closed = true
        s.link.close()
        sessions.remove(s.key, s)
        val offlinePeer: Peer?
        synchronized(stateLock) {
            val p = peerMap[s.key]
            if (p != null && !sessions.containsKey(s.key)) {
                offlinePeer = p.copy(online = false, status = UserStatus.Offline, lastConnection = Instant.now())
                peerMap[s.key] = offlinePeer
                val dc = chatMap[DEFAULT_CHAT_ID]
                if (dc != null) chatMap[DEFAULT_CHAT_ID] = dc.copy(members = dc.members - s.key)
                publishAllLocked()
            } else offlinePeer = null
        }
        if (offlinePeer != null && notify) {
            host.emit(EngineEvent.PeerDisconnected(offlinePeer))
            systemMessageToChatsWith(offlinePeer.key, "${offlinePeer.displayName} is offline.")
            transfers.peerWentOffline(offlinePeer.key)
            scheduleSave()
        }
    }

    // ==== periodic work =======================================================================

    private fun tick() {
        ticks++
        val now = System.currentTimeMillis()
        for (s in sessions.values.toList()) {
            val idle = now - s.link.lastActivityMs
            if (idle > PONG_TIMEOUT_MS) {
                log("Connection timeout with ${peer(s.key)?.displayName} (${idle} ms idle)")
                closeSession(s, true)
                continue
            }
            if (idle >= PING_INTERVAL_IDLE_MS && now - s.lastPing >= PING_PERIOD_MS) {
                s.lastPing = now
                if (!s.link.sendPing()) closeSession(s, true)
            }
        }
        // typing indicators expire
        val t = _typing.value
        if (t.isNotEmpty() && t.any { it.value < now }) _typing.value = t.filterValues { it >= now }

        if (ticks % 20L == 0L) pool.execute { safe { reconnectKnown() } }
        if (ticks % 90L == 0L) pool.execute { safe { discovery?.announce(manualTargets()) } }
        if (ticks % 5L == 0L) {
            val keys = synchronized(stateLock) { pending.map { it.peerKey }.toSet() }
            for (k in keys) if (sessions.containsKey(k)) pool.execute { safe { flushPending(k) } }
        }
    }

    private fun manualTargets(): List<Pair<String, Int>> = emptyList()

    /** Contacts every address we know about: manual list, offline users already seen. */
    private fun reconnectKnown() {
        if (!host.networkAvailable()) return
        for (a in config.manualAddresses) {
            val (h, p) = parseAddress(a) ?: continue
            if (!alreadyConnected(h)) dial(h, p, force = false)
        }
        val offline = synchronized(stateLock) { peerMap.values.filter { !it.online && it.host.isNotEmpty() } }
        for (p in offline) if (!sessions.containsKey(p.key) && !alreadyConnected(p.host)) dial(p.host, p.port, force = false)
    }

    fun searchPeers() {
        if (!_running.value) return
        discovery?.announce()
        reconnectKnown()
    }

    /** Call when Wi-Fi/network changes: sends new beacons and reconnects. */
    fun onNetworkChanged() {
        if (!_running.value) return
        pool.execute { safe { Thread.sleep(1500); searchPeers() } }
    }

    private fun parseAddress(a: String): Pair<String, Int>? {
        val t = a.trim()
        if (t.isEmpty()) return null
        val i = t.lastIndexOf(':')
        return if (i > 0 && t.count { it == ':' } == 1) {
            val p = t.substring(i + 1).toIntOrNull() ?: return null
            t.substring(0, i) to p
        } else t to Proto.DEFAULT_LISTENER_PORT
    }

    // ==== incoming messages ===================================================================

    private fun handle(s: Session, m: BeeMessage) {
        val p = peer(s.key) ?: return
        when (m.type) {
            MessageType.Chat -> onChat(s, p, m)
            MessageType.Received -> onReceived(m)
            MessageType.Read -> onRead(p, m)
            MessageType.User -> onUser(s, p, m)
            MessageType.Group -> onGroup(s, p, m)
            MessageType.File -> transfers.onFileMessage(s.key, p, m, s.link)
            MessageType.Folder -> transfers.onFolderMessage(s.key, p, m, s.link)
            MessageType.Share -> transfers.onShareMessage(p, m, s.link)
            MessageType.ShareBox -> {
                if (!m.hasFlag(Flag.Refused)) s.link.send(Msgs.shareBoxRefuse(Msgs.shareBoxFolderName(m), m.hasFlag(Flag.Create)))
            }
            MessageType.Hive -> onHive(m)
            MessageType.Buzz -> onBuzz(p)
            MessageType.Help -> onHelp(s, p, m)
            MessageType.ShareDesktop -> {
                if (m.hasFlag(Flag.Request)) s.link.send(Msgs.refuseDesktop())
            }
            else -> log("Unhandled message ${m.type} from ${p.displayName}")
        }
    }

    private fun clampTime(t: Instant): Instant {
        val now = Instant.now()
        return if (t.isAfter(now.plus(Duration.ofMinutes(5)))) now else t
    }

    private fun onChat(s: Session, p: Peer, m: BeeMessage) {
        val cd = Msgs.chatDataFrom(m)
        val chat: Chat? = synchronized(stateLock) {
            when {
                m.hasFlag(Flag.Private) -> ensurePrivateChatLocked(p)
                m.hasFlag(Flag.GroupChat) -> chatMap[groupChatId(cd.groupId)]
                else -> chatMap[DEFAULT_CHAT_ID]
            }
        }
        if (chat == null) {
            log("Chat message from ${p.displayName} for an unknown chat dropped")
            return
        }
        if (chat.type == ChatType.GROUP && !chat.members.contains(p.key)) {
            log("${p.displayName} is not a member of ${chat.name}: message dropped")
            return
        }
        val auto = m.hasFlag(Flag.Auto)
        val msg = ChatMessage(
            localId = nextLocalId(), chatId = chat.id, senderKey = p.key,
            text = Msgs.unescapeIncoming(m.text), time = clampTime(m.timestamp),
            kind = MsgKind.TEXT, wireId = m.id, important = m.hasFlag(Flag.Important),
            sourceCode = m.hasFlag(Flag.SourceCode), auto = auto,
        )
        val stored = synchronized(stateLock) {
            // typing indicator is over
            _typing.value = _typing.value - "${chat.id}|${p.key}"
            addMessageLocked(chat.id, msg, countUnread = true)
        }
        if (p.protocolVersion >= Proto.RECEIVED_MESSAGE && s.link.protoVersion >= Proto.RECEIVED_MESSAGE) s.link.send(Msgs.received(m))
        if (openChatId == chat.id && s.link.protoVersion > 62) s.link.send(Msgs.chatRead(chat)) // the chat is on screen: already read
        val updated = chat(chat.id) ?: chat
        host.emit(EngineEvent.NewMessage(updated, stored, p))
    }

    private fun onReceived(m: BeeMessage) {
        val id = m.text.trim().toLongOrNull() ?: return
        val ref = synchronized(stateLock) { wireToLocal[id] } ?: return
        updateMessage(ref.first, ref.second) { if (it.delivery < Delivery.DELIVERED) it.copy(delivery = Delivery.DELIVERED) else it }
    }

    private fun onRead(p: Peer, m: BeeMessage) {
        val cd = Msgs.chatDataFrom(m)
        val chatId = when {
            m.hasFlag(Flag.Private) -> privateChatId(p.key)
            m.hasFlag(Flag.GroupChat) -> groupChatId(cd.groupId)
            else -> DEFAULT_CHAT_ID
        }
        synchronized(stateLock) {
            val c = chatMap[chatId] ?: return
            if (c.type == ChatType.PRIVATE) {
                val list = messageMap[chatId] ?: return
                var changed = false
                for (i in list.indices) {
                    val it = list[i]
                    if (it.senderKey == null && it.kind == MsgKind.TEXT && it.delivery < Delivery.READ && it.delivery != Delivery.QUEUED && it.delivery != Delivery.FAILED) {
                        list[i] = it.copy(delivery = Delivery.READ); changed = true
                    }
                }
                if (changed) publishMessagesLocked(chatId)
            }
            chatMap[chatId] = c.copy(readByOthers = c.readByOthers + p.key)
            publishChatsLocked()
        }
    }

    private fun onUser(s: Session, p: Peer, m: BeeMessage) {
        when {
            m.hasFlag(Flag.UserWriting) -> {
                val chatId = if (m.data.isEmpty()) privateChatId(p.key)
                else synchronized(stateLock) {
                    chatMap.values.firstOrNull { (it.type != ChatType.PRIVATE) && it.privateId == m.data }?.id
                } ?: privateChatId(p.key)
                _typing.value = _typing.value + ("$chatId|${p.key}" to System.currentTimeMillis() + TYPING_MS)
            }
            m.hasFlag(Flag.UserStatus) -> {
                val st = m.data.trim().toIntOrNull() ?: return
                synchronized(stateLock) {
                    val cur = peerMap[p.key] ?: return
                    peerMap[p.key] = cur.copy(status = st, statusDescription = m.text)
                    publishPeersLocked()
                }
                scheduleSave()
            }
            m.hasFlag(Flag.UserVCard) -> {
                val vc = Msgs.vcardFrom(m, p.protocolVersion) ?: return
                synchronized(stateLock) {
                    val cur = peerMap[p.key] ?: return
                    var np = cur.copy(vcard = vc.vcard)
                    if (vc.color != null && Regex("#[0-9a-fA-F]{6}").matches(vc.color)) np = np.copy(color = vc.color)
                    if (vc.helper != null) np = np.copy(isHelper = vc.helper)
                    val nick = vc.vcard.nickName
                    if (nick.isNotEmpty() && nick != cur.name) {
                        np = np.copy(name = nick)
                        val pc = chatMap[privateChatId(p.key)]
                        if (pc != null) chatMap[pc.id] = pc.copy(name = nick)
                        publishChatsLocked()
                    }
                    peerMap[p.key] = np
                    publishPeersLocked()
                }
                scheduleSave()
            }
            else -> log("User message with unknown flags ${m.flags} from ${p.displayName}")
        }
    }

    private fun onBuzz(p: Peer) {
        val chatId = privateChatId(p.key)
        val msg = synchronized(stateLock) {
            ensurePrivateChatLocked(p)
            addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, p.key, "${p.displayName} is buzzing you.", Instant.now(), MsgKind.BUZZ), true)
        }
        host.emit(EngineEvent.Buzz(p, chatId))
        host.emit(EngineEvent.NewMessage(chat(chatId)!!, msg, p))
    }

    private fun onHelp(s: Session, p: Peer, m: BeeMessage) {
        val chatId = privateChatId(p.key)
        if (m.hasFlag(Flag.Request)) {
            if (!config.acceptHelpRequests) return
            synchronized(stateLock) {
                ensurePrivateChatLocked(p)
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, p.key, "${p.displayName} is asking for your help. ${m.text}", Instant.now(), MsgKind.HELP, important = true), true)
            }
            host.emit(EngineEvent.HelpRequest(p, chatId))
            s.link.send(Msgs.helpAnswer("I got your call for help."))
        } else if (m.hasFlag(Flag.Create)) {
            systemMessage(chatId, "${p.displayName} got your call for help.", MsgKind.HELP)
        }
    }

    private fun onHive(m: BeeMessage) {
        if (!m.hasFlag(Flag.List)) return
        for ((h, port) in Msgs.hiveFrom(m)) {
            if (!alreadyConnected(h)) pool.execute { safe { dial(h, port, force = false) } }
        }
    }

    // ---- group messages ------------------------------------------------------------------

    private fun resolvePeer(name: String, account: String, hash: String, domain: String, create: Boolean): Peer? {
        synchronized(stateLock) {
            val key = peerKey(account.ifEmpty { name }, domain)
            if (key == localKey) return null
            peerMap[key]?.let { return it }
            // a user of the "new generation" is identified by its hash only
            if (hash.isNotEmpty()) peerMap.values.firstOrNull { it.hash == hash }?.let { return it }
            peerMap.values.firstOrNull { it.name.equals(name, true) && it.domain.equals(domain, true) }?.let { return it }
            if (!create) return null
            val p = Peer(key, name, account.ifEmpty { name.lowercase() }, domain, hash)
            peerMap[key] = p
            ensurePrivateChatLocked(p)
            publishAllLocked()
            return p
        }
    }

    private fun onGroup(s: Session, sender: Peer, m: BeeMessage) {
        val cd = Msgs.chatDataFrom(m)
        when {
            m.hasFlag(Flag.Request) -> onGroupRequest(s, sender, m, cd)
            m.hasFlag(Flag.Refused) -> {
                val id = groupChatId(cd.groupId)
                synchronized(stateLock) {
                    val c = chatMap[id] ?: return
                    if (c.members.contains(sender.key)) {
                        chatMap[id] = c.copy(members = c.members - sender.key, lastModified = Instant.now())
                        addMessageLocked(id, ChatMessage(nextLocalId(), id, null, "${sender.displayName} has left the group chat ${c.name}.", Instant.now(), MsgKind.SYSTEM), false)
                        pending.removeAll { it.chatId == id && it.peerKey == sender.key }
                    }
                }
            }
            m.hasFlag(Flag.Delete) -> {
                val id = groupChatId(cd.groupId)
                synchronized(stateLock) {
                    val c = chatMap[id] ?: return
                    chatMap[id] = c.copy(localUserIsMember = false)
                    refusedChats.add(c.privateId)
                    addMessageLocked(id, ChatMessage(nextLocalId(), id, null, "${sender.displayName} has removed you from the group chat ${c.name}.", Instant.now(), MsgKind.SYSTEM), false)
                }
                scheduleSave()
            }
            else -> log("Group message with invalid flags from ${sender.displayName}")
        }
    }

    private fun onGroupRequest(s: Session, sender: Peer, m: BeeMessage, cd: Msgs.ChatData) {
        val chatId = groupChatId(cd.groupId)
        val refused = synchronized(stateLock) { refusedChats.contains(cd.groupId) }
        if (refused) {
            s.link.send(Msgs.groupRefuse(cd.groupId, cd.groupName))
            return
        }
        val existing = chat(chatId)
        if (existing != null) {
            val newer = cd.groupLastModified
            val cur = existing.lastModified
            if (newer != null && cur != null) {
                if (!newer.isAfter(cur)) return
            } else if (newer == null && cur != null) return
            if (!existing.localUserIsMember) {
                synchronized(stateLock) { refusedChats.add(existing.privateId) }
                s.link.send(Msgs.groupRefuse(cd.groupId, cd.groupName))
                return
            }
        }
        val members = LinkedHashSet<String>()
        members.add(sender.key)
        if (sender.protocolVersion < Proto.NEW_GROUP) {
            for (path in m.text.split(Proto.PARAGRAPH).filter { it.isNotEmpty() }) {
                val name = path.substringBefore('@')
                val found = synchronized(stateLock) { peerMap.values.firstOrNull { it.name.equals(name, true) } }
                    ?: resolvePeer(name, name.lowercase(), "", "", true)
                if (found != null && found.key != localKey) members.add(found.key)
            }
        } else {
            for (r in Msgs.memberRecordsFrom(m)) {
                val p = resolvePeer(r.name, r.account, r.hash, r.domain, true) ?: continue
                members.add(p.key)
            }
        }
        synchronized(stateLock) {
            if (existing != null) {
                val added = members - existing.members
                val removed = existing.members - members
                var nc = existing
                if (cd.groupName.isNotEmpty() && cd.groupName != existing.name) {
                    addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "${sender.displayName} has changed the group name from ${existing.name} to ${cd.groupName}.", Instant.now(), MsgKind.SYSTEM), false)
                    nc = nc.copy(name = cd.groupName)
                }
                if (removed.isNotEmpty()) addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "${sender.displayName} has removed members: ${removed.mapNotNull { peerMap[it]?.displayName }.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
                if (added.isNotEmpty()) addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "${sender.displayName} has added members: ${added.mapNotNull { peerMap[it]?.displayName }.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
                chatMap[chatId] = nc.copy(members = members, lastModified = cd.groupLastModified ?: nc.lastModified)
            } else {
                val name = cd.groupName.ifEmpty { "Group" }
                chatMap[chatId] = Chat(chatId, ChatType.GROUP, name, cd.groupId, members, cd.groupLastModified)
                messageMap[chatId] = ArrayList()
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "${sender.displayName} has added you to the group chat $name.", Instant.now(), MsgKind.SYSTEM), false)
                addMessageLocked(chatId, ChatMessage(nextLocalId(), chatId, null, "Chat with ${members.mapNotNull { peerMap[it]?.displayName }.joinToString(", ")}.", Instant.now(), MsgKind.SYSTEM), false)
            }
            publishAllLocked()
        }
        scheduleSave()
    }

    // ==== file transfer API ===================================================================

    fun sendFiles(chatId: String, files: List<LocalFile>): Int = transfers.sendFiles(chatId, files)
    fun sendFolder(chatId: String, treeUri: String, folderName: String): Int = transfers.sendFolder(chatId, treeUri, folderName)
    fun sendVoice(chatId: String, path: String, durationMs: Long): Boolean = transfers.sendVoice(chatId, path, durationMs)
    fun acceptFile(messageLocalId: Long) = transfers.acceptOffer(messageLocalId)
    fun declineFile(messageLocalId: Long) = transfers.declineOffer(messageLocalId)
    fun cancelTransfer(key: String) = transfers.cancel(key)
    fun clearFinishedTransfers() = transfers.clearFinished()
    fun requestSharedFiles() = transfers.requestShares()
    fun shareFiles(files: List<LocalFile>) = transfers.addShared(files)
    fun unshareFile(uri: String) = transfers.removeShared(uri)
    fun downloadShared(file: SharedFile) = transfers.downloadShared(file)

    // ==== helpers for Transfers ===============================================================

    internal fun sessionFor(peerKey: String): Session? = sessions[peerKey]
    internal fun pool() = pool
    internal fun isKnownPeer(key: String) = synchronized(stateLock) { peerMap.containsKey(key) }
    internal fun runtimeRunning() = _running.value
    internal fun newLocalId() = nextLocalId()

    internal fun chatForFile(peerKey: String, chatPrivateId: String): String {
        if (chatPrivateId.isNotEmpty()) {
            synchronized(stateLock) {
                chatMap.values.firstOrNull { it.type == ChatType.GROUP && it.privateId == chatPrivateId && it.members.contains(peerKey) }?.let { return it.id }
                if (chatPrivateId == Proto.DEFAULT_CHAT_PRIVATE_ID) return DEFAULT_CHAT_ID
            }
        }
        return privateChatId(peerKey)
    }

    internal fun ensurePrivateChat(peerKey: String) {
        synchronized(stateLock) {
            val p = peerMap[peerKey] ?: return
            ensurePrivateChatLocked(p)
            publishChatsLocked()
        }
    }

    internal fun emit(e: EngineEvent) = host.emit(e)

    companion object {
        const val DEFAULT_CHAT_ID = "default"
        const val MAX_STORED_MESSAGES = 800
        const val HANDSHAKE_TIMEOUT_MS = 12_000
        const val CONNECT_TIMEOUT_MS = 6_000
        const val PONG_TIMEOUT_MS = 24_000L
        const val PING_INTERVAL_IDLE_MS = 4_700L
        const val PING_PERIOD_MS = 7_000L
        const val TYPING_MS = 4_000L
    }
}
