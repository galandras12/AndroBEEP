package com.galandras12.droidbeep.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File, folder, voice message and file-share transfers (FileTransfer / FileTransferPeer of BeeBEEP).
 *
 * Every transfer uses a dedicated TCP connection to the *sender's* file transfer port: the downloader dials
 * in, repeats the HELLO exchange, sends a BEE-FILE request (id + password), the uploader answers with a
 * file header and then data blocks, each block being confirmed by the downloader.
 */
internal class Transfers(private val e: BeeEngine) {

    private val _list = MutableStateFlow<List<TransferInfo>>(emptyList())
    val list: StateFlow<List<TransferInfo>> = _list.asStateFlow()
    private val _remoteShares = MutableStateFlow<List<SharedFile>>(emptyList())
    val remoteShares: StateFlow<List<SharedFile>> = _remoteShares.asStateFlow()
    private val _localShares = MutableStateFlow<List<LocalFile>>(emptyList())
    val localShares: StateFlow<List<LocalFile>> = _localShares.asStateFlow()

    var serverPort: Int = 0
        private set
    private var server: ServerSocket? = null

    private class Outgoing(val info: BeeFileInfo, val file: LocalFile)

    private val outgoing = ConcurrentHashMap<Long, Outgoing>()
    private val sharedIds = ConcurrentHashMap<String, Long>() // uri -> id of shared files

    /** A chat message that tracks the upload of one file or folder to one or more recipients. */
    private class OutMsg(val chatId: String, val localId: Long, val recipients: Set<String>, val fileIds: List<Long>, val totalBytes: Long) {
        val progress = ConcurrentHashMap<String, Long>()   // "fileId|peer" -> bytes
        val finished = ConcurrentHashMap<String, Boolean>() // "fileId|peer" -> ok
    }

    private val outMsgs = ConcurrentHashMap<Long, MutableList<OutMsg>>() // fileId -> messages

    private class Offer(
        val localId: Long, val chatId: String, val peerKey: String, val files: List<BeeFileInfo>,
        val folderName: String, val protoVersion: Int, val newGen: Boolean,
    ) {
        val canceled = AtomicBoolean(false)
    }

    private val offers = ConcurrentHashMap<Long, Offer>()
    private val canceledKeys = ConcurrentHashMap.newKeySet<String>()

    // ---- lifecycle -----------------------------------------------------------------------------------

    fun start(port: Int) {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            try { ss.bind(InetSocketAddress(port)) } catch (ex: IOException) { ss.bind(InetSocketAddress(0)) }
            server = ss
            serverPort = ss.localPort
            e.pool().execute { acceptLoop(ss) }
        } catch (ex: IOException) {
            e.log("File transfer listener error: ${ex.message}")
        }
        registerSharedFiles()
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        canceledKeys.addAll(_list.value.filter { it.state == TransferState.TRANSFERRING || it.state == TransferState.CONNECTING }.map { it.key })
    }

    fun loadShares() {
        _localShares.value = e.host.storage.loadSharedFiles()
    }

    private fun registerSharedFiles() {
        for (f in _localShares.value) registerShared(f)
    }

    private fun registerShared(f: LocalFile): BeeFileInfo {
        val id = sharedIds.getOrPut(f.uri) { Msgs.newId() }
        return outgoing.getOrPut(id) {
            val info = BeeFileInfo(
                id = id, name = f.name, size = f.size, password = Crypto.hash(f.uri + id, e.passwordHashBytes),
                fileHash = Crypto.simpleHash("${f.name}-${f.size}-${f.lastModified}"), shareFolder = f.shareFolder,
                lastModified = if (f.lastModified > 0) Instant.ofEpochMilli(f.lastModified) else null, mimeType = f.mimeType,
                hostPort = serverPort,
            )
            Outgoing(info, f)
        }.info
    }

    // ---- bookkeeping ------------------------------------------------------------------------------

    private fun upsert(t: TransferInfo) {
        synchronized(this) {
            val l = _list.value.toMutableList()
            val i = l.indexOfFirst { it.key == t.key }
            if (i >= 0) l[i] = t else l.add(0, t)
            while (l.size > 60) {
                val idx = l.indexOfLast { it.state == TransferState.COMPLETED || it.state == TransferState.ERROR || it.state == TransferState.CANCELED || it.state == TransferState.REFUSED }
                if (idx < 0) break
                l.removeAt(idx)
            }
            _list.value = l
        }
    }

    private fun transferSpeed(startedAt: Long, bytes: Long): Long {
        val dt = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
        return bytes * 1000 / dt
    }

    fun cancel(key: String) {
        canceledKeys.add(key)
        val t = _list.value.firstOrNull { it.key == key } ?: return
        if (t.state == TransferState.QUEUED || t.state == TransferState.OFFERED) upsert(t.copy(state = TransferState.CANCELED))
    }

    fun clearFinished() {
        synchronized(this) {
            _list.value = _list.value.filter { it.state == TransferState.TRANSFERRING || it.state == TransferState.CONNECTING || it.state == TransferState.QUEUED }
        }
    }

    fun peerWentOffline(peerKey: String) {
        // transfers have their own connections, nothing to do
    }

    // ===== sending =====================================================================================

    private fun recipientsOfChat(chatId: String): List<String> {
        val c = e.chat(chatId) ?: return emptyList()
        return when (c.type) {
            ChatType.DEFAULT -> e.peers.value.filter { it.online }.map { it.key }
            else -> c.members.toList()
        }
    }

    /** Registers [f] for upload and returns its info. */
    private fun registerOutgoing(f: LocalFile, chatPrivateId: String, contentType: Int = 0, duration: Long = -1): BeeFileInfo {
        val id = Msgs.newId()
        val rnd = (111111..999999).random()
        val info = BeeFileInfo(
            id = id, name = f.name, size = f.size,
            password = Crypto.hash("$rnd$id${(111111..999999).random()}${f.uri}${(111111..999999).random()}${f.size}", e.passwordHashBytes),
            fileHash = Crypto.simpleHash("${f.name}-${f.size}-${f.lastModified}"),
            shareFolder = f.shareFolder, chatPrivateId = chatPrivateId,
            lastModified = if (f.lastModified > 0) Instant.ofEpochMilli(f.lastModified) else null,
            mimeType = f.mimeType, contentType = contentType, duration = duration, hostPort = serverPort,
        )
        outgoing[id] = Outgoing(info, f)
        return info
    }

    fun sendFiles(chatId: String, files: List<LocalFile>): Int {
        val chat = e.chat(chatId) ?: return 0
        var count = 0
        val recipients = recipientsOfChat(chatId).filter { e.sessionFor(it) != null }
        if (recipients.isEmpty()) {
            e.systemMessage(chatId, "Unable to send files: nobody is online in this chat.")
            return 0
        }
        for (f in files) {
            if (f.size <= 0) {
                e.systemMessage(chatId, "Unable to send ${f.name}: the file is empty.")
                continue
            }
            val chatPid = if (chat.type == ChatType.PRIVATE) "" else chat.privateId
            val info = registerOutgoing(f, chatPid)
            val ref = FileRef(info.id, f.name, f.size, TransferState.QUEUED, upload = true, localPath = null, mimeType = f.mimeType)
            val text = "Sending ${f.name} to ${namesOf(recipients)}"
            val msg = e.addFileMessage(chatId, null, text, ref, MsgKind.FILE, false)
            val om = OutMsg(chatId, msg.localId, recipients.toSet(), listOf(info.id), f.size * recipients.size)
            outMsgs.getOrPut(info.id) { ArrayList() }.add(om)
            var sentTo = 0
            for (k in recipients) {
                val s = e.sessionFor(k) ?: continue
                val m = Msgs.fileInfoToMessage(info, s.link.protoVersion, s.link.isNewGenerationPeer)
                if (s.link.send(m)) sentTo++ else om.finished["${info.id}|$k"] = false
            }
            if (sentTo == 0) {
                e.updateMessage(chatId, msg.localId) { it.copy(file = it.file?.copy(state = TransferState.ERROR, error = "not sent")) }
            } else count++
        }
        return count
    }

    private fun namesOf(keys: List<String>): String = keys.mapNotNull { e.peer(it)?.displayName }.joinToString(", ").ifEmpty { "everyone" }

    fun sendFolder(chatId: String, treeUri: String, folderName: String): Int {
        val chat = e.chat(chatId) ?: return 0
        val recipients = recipientsOfChat(chatId).filter { e.sessionFor(it) != null }
        if (recipients.isEmpty()) { e.systemMessage(chatId, "Unable to send the folder: nobody is online in this chat."); return 0 }
        val files = e.host.listFolder(treeUri, folderName).filter { it.size > 0 }
        if (files.isEmpty()) { e.systemMessage(chatId, "Unable to send folder $folderName: the folder is empty."); return 0 }
        val chatPid = if (chat.type == ChatType.PRIVATE) "" else chat.privateId
        val infos = files.map { registerOutgoing(it, chatPid) }
        val total = files.sumOf { it.size }
        val ref = FileRef(infos.first().id, folderName, total, TransferState.QUEUED, upload = true, folderName = folderName, fileCount = files.size)
        val msg = e.addFileMessage(chatId, null, "Sending folder $folderName (${files.size} files) to ${namesOf(recipients)}", ref, MsgKind.FILE, false)
        val om = OutMsg(chatId, msg.localId, recipients.toSet(), infos.map { it.id }, total * recipients.size)
        for (i in infos) outMsgs.getOrPut(i.id) { ArrayList() }.add(om)
        var sentTo = 0
        for (k in recipients) {
            val s = e.sessionFor(k) ?: continue
            if (s.link.send(Msgs.folderMessage(folderName, infos, serverPort))) sentTo++
        }
        if (sentTo == 0) e.updateMessage(chatId, msg.localId) { it.copy(file = it.file?.copy(state = TransferState.ERROR, error = "not sent")) }
        return files.size
    }

    /** Records a voice message, already saved in [path], and sends it to the chat. */
    fun sendVoice(chatId: String, path: String, durationMs: Long): Boolean {
        val chat = e.chat(chatId) ?: return false
        val file = File(path)
        if (!file.exists() || file.length() <= 0) return false
        val recipients = when (chat.type) {
            ChatType.DEFAULT -> recipientsOfChat(chatId)
            else -> chat.members.toList()
        }
        val ref = FileRef(0, file.name, file.length(), TransferState.QUEUED, upload = true, localPath = path, mimeType = "audio/wav", durationMs = durationMs)
        val msg = e.addFileMessage(chatId, null, "Voice message", ref, MsgKind.VOICE, false)
        var any = false
        for (k in recipients) {
            if (e.sessionFor(k) != null) {
                if (sendVoiceTo(k, chatId, path, durationMs, msg.localId)) any = true
            } else if (chat.type != ChatType.DEFAULT) {
                e.queueVoice(k, chatId, msg.localId, path, durationMs)
                any = true
            }
        }
        if (!any) e.updateMessage(chatId, msg.localId) { it.copy(file = it.file?.copy(state = TransferState.ERROR, error = "nobody online")) }
        return any
    }

    /** Sends the BEE-FILE message of a voice message to one user (also used for queued voice messages). */
    fun sendVoiceTo(peerKey: String, chatId: String, path: String, durationMs: Long, existingLocalId: Long): Boolean {
        val s = e.sessionFor(peerKey) ?: return false
        val file = File(path)
        if (!file.exists()) return false
        val chat = e.chat(chatId)
        val lf = LocalFile(path, file.name, file.length(), file.lastModified(), "audio/wav")
        val info = registerOutgoing(lf, if (chat == null || chat.type == ChatType.PRIVATE) "" else chat.privateId, 1, durationMs)
        val om = OutMsg(chatId, existingLocalId, setOf(peerKey), listOf(info.id), file.length())
        outMsgs.getOrPut(info.id) { ArrayList() }.add(om)
        return s.link.send(Msgs.fileInfoToMessage(info, s.link.protoVersion, s.link.isNewGenerationPeer))
    }

    private fun outProgress(fileId: Long, peerKey: String, bytes: Long, done: Boolean?) {
        val list = outMsgs[fileId] ?: return
        for (om in list.toList()) {
            val k = "$fileId|$peerKey"
            if (!om.recipients.contains(peerKey)) continue
            om.progress[k] = bytes
            if (done != null) om.finished[k] = done
            val total = om.totalBytes.coerceAtLeast(1)
            val sum = om.progress.values.sum()
            val expected = om.fileIds.size * om.recipients.size
            val finishedAll = om.finished.size >= expected
            val ok = om.finished.values.count { it }
            val state = when {
                finishedAll && ok > 0 -> TransferState.COMPLETED
                finishedAll -> TransferState.ERROR
                else -> TransferState.TRANSFERRING
            }
            e.updateMessage(om.chatId, om.localId) { m ->
                val f = m.file ?: return@updateMessage m
                m.copy(file = f.copy(state = if (f.state == TransferState.REFUSED) f.state else state, transferred = sum.coerceAtMost(total)))
            }
        }
    }

    // ===== upload server ==================================================================================

    private fun acceptLoop(ss: ServerSocket) {
        while (e.runtimeRunning()) {
            try {
                val sock = ss.accept()
                e.pool().execute { serve(sock) }
            } catch (ex: IOException) {
                if (e.runtimeRunning() && !ss.isClosed) {
                    e.log("File transfer accept error: ${ex.message}")
                    try { Thread.sleep(300) } catch (_: InterruptedException) { return }
                } else return
            }
        }
    }

    private fun serve(sock: Socket) {
        sock.tcpNoDelay = true
        val link = BeeLink(sock, { e.identity() }, { e.passwordHashBytes }, initiator = false)
        var key = ""
        var fileId = 0L
        var started = 0L
        try {
            sock.soTimeout = BeeEngine.HANDSHAKE_TIMEOUT_MS
            val hello = link.handshake()
            key = e.peerKey(hello.accountName, hello.domainName, hello.name)
            if (!e.isKnownPeer(key)) throw IOException("file transfer from an unknown user")
            sock.soTimeout = TRANSFER_TIMEOUT_MS
            val reqMsg = link.receive()
            if (reqMsg.type != MessageType.File) throw IOException("file request expected")
            val req = Msgs.fileInfoFrom(reqMsg, link.protoVersion) ?: throw IOException("invalid file request")
            val out = outgoing[req.id] ?: throw IOException("requested file ${req.id} is not available")
            if (out.info.password != req.password) throw IOException("wrong password for ${out.info.name}")
            fileId = out.info.id
            val size = out.file.size
            val start = req.startingPosition.coerceIn(0, size)
            val tkey = "u$fileId|$key|${System.nanoTime()}"
            started = System.currentTimeMillis()
            if (start == size && size > 0) {
                outProgress(fileId, key, size, true)
                return
            }
            upsert(TransferInfo(tkey, key, out.info.name, size, start, true, TransferState.CONNECTING, startedAt = started))
            val header = out.info.copy().apply { this.size = size; startingPosition = start; hostPort = serverPort }
            if (link.protoVersion >= Proto.FILE_TRANSFER_2) {
                if (!link.send(Msgs.fileInfoToMessage(header, link.protoVersion, link.isNewGenerationPeer))) throw IOException("cannot send the file header")
            }
            val input = e.host.openInput(out.file.uri, start) ?: throw IOException("cannot open ${out.file.name}")
            input.use { ins ->
                var bytesTransferred = start
                var total = 0L
                val buf = ByteArray(BUFFER_SIZE)
                upsert(TransferInfo(tkey, key, out.info.name, size, start, true, TransferState.TRANSFERRING, startedAt = started))
                while (true) {
                    if (canceledKeys.remove(tkey)) throw IOException("canceled")
                    val conf = Msgs.parseBytesArrived(link.protoVersion, link.receiveBytes()) ?: throw IOException("invalid confirmation")
                    if (conf.bytes <= 0 && conf.pause) throw IOException("paused by the remote user")
                    if (conf.bytes != bytesTransferred) throw IOException("$bytesTransferred bytes sent but ${conf.bytes} bytes confirmed")
                    total += bytesTransferred
                    if (conf.total > 0 && total != conf.total) throw IOException("$total bytes uploaded but the remote file size is ${conf.total}")
                    if (total > size) throw IOException("$total bytes uploaded but the file size is only $size")
                    upsert(TransferInfo(tkey, key, out.info.name, size, total, true, TransferState.TRANSFERRING, startedAt = started, bytesPerSecond = transferSpeed(started, total - start)))
                    outProgress(fileId, key, total, null)
                    if (total == size) break
                    var n = 0
                    val want = minOf(BUFFER_SIZE.toLong(), size - total).toInt()
                    while (n < want) {
                        val r = ins.read(buf, n, want - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n <= 0) throw IOException("unexpected end of file")
                    link.sendBytes(if (n == buf.size) buf else buf.copyOf(n))
                    bytesTransferred = n.toLong()
                }
                upsert(TransferInfo(tkey, key, out.info.name, size, size, true, TransferState.COMPLETED, startedAt = started))
                outProgress(fileId, key, size, true)
            }
        } catch (ex: Exception) {
            e.log("Upload to $key: ${ex.message}")
            if (fileId != 0L && key.isNotEmpty() && started != 0L) {
                outProgress(fileId, key, outMsgs[fileId]?.firstOrNull()?.progress?.get("$fileId|$key") ?: 0, false)
                val cur = _list.value.firstOrNull { it.key.startsWith("u$fileId|$key|") && it.state != TransferState.COMPLETED }
                if (cur != null) upsert(cur.copy(state = TransferState.ERROR, error = ex.message ?: "error"))
            }
        } finally {
            link.close()
        }
    }

    // ===== incoming offers =====================================================================================

    fun onFileMessage(peerKey: String, peer: Peer, m: BeeMessage, link: BeeLink) {
        val fi = Msgs.fileInfoFrom(m, link.protoVersion) ?: run { e.log("Invalid file info from ${peer.displayName}"); return }
        val chatId = e.chatForFile(peerKey, fi.chatPrivateId)
        if (m.hasFlag(Flag.Refused)) {
            val reason = m.data.split(Proto.LINE).getOrNull(14)
            val suffix = if (reason == Proto.FILE_REFUSE_REASON_POLICY) " (not allowed)" else ""
            e.systemMessage(chatId, "${peer.displayName} has refused to download the file: ${fi.name}$suffix")
            // update our outgoing message state
            outMsgs[fi.id]?.forEach { om ->
                if (om.recipients.contains(peer.key)) {
                    om.finished["${fi.id}|${peer.key}"] = false
                    outProgress(fi.id, peer.key, om.progress["${fi.id}|${peer.key}"] ?: 0, false)
                    e.updateMessage(om.chatId, om.localId) { msg -> msg.copy(file = msg.file?.copy(state = if (om.finished.size >= om.fileIds.size * om.recipients.size && om.finished.values.none { it }) TransferState.REFUSED else msg.file.state)) }
                }
            }
            return
        }
        if (fi.name.isEmpty() || !fi.name.endsWith(fi.suffix, ignoreCase = true) && fi.suffix.isNotEmpty()) return
        fi.hostAddress = peer.host.ifEmpty { link.peerHostAddress }
        fi.name = safeName(fi.name)
        val voice = m.hasFlag(Flag.VoiceMessage) || fi.isVoiceMessage
        val ref = FileRef(fi.id, fi.name, fi.size, TransferState.OFFERED, upload = false, mimeType = fi.mimeType, durationMs = fi.duration)
        val msg = e.addFileMessage(chatId, peerKey, if (voice) "Voice message" else fi.name, ref, if (voice) MsgKind.VOICE else MsgKind.FILE, true)
        val offer = Offer(msg.localId, chatId, peerKey, listOf(fi), "", link.protoVersion, link.isNewGenerationPeer)
        offers[msg.localId] = offer
        e.emit(EngineEvent.FileOffer(peer, msg))
        if (voice && e.config.autoAcceptVoiceMessages) acceptOffer(msg.localId)
    }

    fun onFolderMessage(peerKey: String, peer: Peer, m: BeeMessage, link: BeeLink) {
        if (m.hasFlag(Flag.Refused)) {
            val chatId = e.chatForFile(peerKey, m.data)
            e.systemMessage(chatId, "${peer.displayName} has refused to download folder ${m.text}.")
            return
        }
        if (!m.hasFlag(Flag.Request)) return
        val (folderName, files) = Msgs.folderFrom(m) ?: return
        if (files.isEmpty()) return
        val host = peer.host.ifEmpty { link.peerHostAddress }
        files.forEach { it.hostAddress = host; it.name = safeName(it.name) }
        val chatId = e.chatForFile(peerKey, files.first().chatPrivateId)
        val total = files.sumOf { it.size }
        val ref = FileRef(files.first().id, folderName, total, TransferState.OFFERED, upload = false, folderName = folderName, fileCount = files.size)
        val msg = e.addFileMessage(chatId, peerKey, "Folder $folderName (${files.size} files)", ref, MsgKind.FILE, true)
        offers[msg.localId] = Offer(msg.localId, chatId, peerKey, files, folderName, link.protoVersion, link.isNewGenerationPeer)
        e.emit(EngineEvent.FileOffer(peer, msg))
    }

    fun declineOffer(localId: Long) {
        val o = offers.remove(localId) ?: return
        val s = e.sessionFor(o.peerKey)
        if (s != null) {
            if (o.folderName.isNotEmpty()) s.link.send(Msgs.folderRefused(o.folderName, o.files.first().chatPrivateId))
            else for (f in o.files) s.link.send(Msgs.fileInfoRefused(f, s.link.protoVersion, s.link.isNewGenerationPeer, Proto.FILE_REFUSE_REASON_USER))
        }
        e.updateMessage(o.chatId, localId) { it.copy(file = it.file?.copy(state = TransferState.REFUSED)) }
    }

    fun acceptOffer(localId: Long) {
        val o = offers.remove(localId) ?: return
        e.updateMessage(o.chatId, localId) { it.copy(file = it.file?.copy(state = TransferState.QUEUED)) }
        e.pool().execute { runDownloads(o) }
    }

    private fun safeName(n: String): String = n.replace('\\', '/').substringAfterLast('/').ifEmpty { "file" }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun runDownloads(o: Offer) {
        var totalDone = 0L
        val totalSize = o.files.sumOf { it.size }.coerceAtLeast(1)
        var lastPath: String? = null
        var failed: String? = null
        for ((index, fi) in o.files.withIndex()) {
            val voice = fi.isVoiceMessage
            val base: File = if (voice) File(e.host.cacheDir(), "voice") else e.host.downloadDir()
            val dir = if (fi.shareFolder.isNotEmpty() && o.folderName.isNotEmpty()) File(base, fi.shareFolder.split('/').filter { it.isNotEmpty() && it != ".." && it != "." }.joinToString("/")) else base
            val key = "d${o.localId}-$index"
            try {
                val doneBefore = totalDone
                val target = uniqueFile(dir, fi.name)
                downloadOne(o, fi, target, key) { got ->
                    e.updateMessage(o.chatId, o.localId) { m ->
                        m.copy(file = m.file?.copy(state = TransferState.TRANSFERRING, transferred = (doneBefore + got).coerceAtMost(totalSize)))
                    }
                }
                totalDone += fi.size
                lastPath = target.absolutePath
                if (!voice) e.emit(EngineEvent.FileReceived(target.absolutePath, target.name, fi.mimeType))
            } catch (ex: Exception) {
                failed = ex.message ?: ex.javaClass.simpleName
                e.log("Download of ${fi.name} failed: $failed")
                val cur = _list.value.firstOrNull { it.key == key }
                upsert((cur ?: TransferInfo(key, o.peerKey, fi.name, fi.size, 0, false, TransferState.ERROR)).copy(state = TransferState.ERROR, error = failed))
                if (o.folderName.isEmpty()) break
            }
        }
        e.updateMessage(o.chatId, o.localId) { m ->
            val f = m.file ?: return@updateMessage m
            if (failed != null && lastPath == null) m.copy(file = f.copy(state = TransferState.ERROR, error = failed))
            else m.copy(file = f.copy(state = TransferState.COMPLETED, transferred = totalSize, localPath = lastPath, error = failed ?: ""))
        }
    }

    /** Downloads one file into [target] (the final location); progress callback gets the bytes of this file. */
    private fun downloadOne(o: Offer?, fi: BeeFileInfo, target: File, key: String, onProgress: (Long) -> Unit) {
        val peerKey = o?.peerKey ?: ""
        val started = System.currentTimeMillis()
        upsert(TransferInfo(key, peerKey, fi.name, fi.size, 0, false, TransferState.CONNECTING, startedAt = started))
        var link: BeeLink? = null
        val part = File(e.host.cacheDir(), "${fi.fileHash.ifEmpty { fi.id.toString() }}.${System.nanoTime()}.part")
        try {
            part.parentFile?.mkdirs()
            link = BeeLink.connect(fi.hostAddress, fi.hostPort, 8000, { e.identity() }, { e.passwordHashBytes })
            link.socket.soTimeout = BeeEngine.HANDSHAKE_TIMEOUT_MS
            link.handshake()
            link.socket.soTimeout = TRANSFER_TIMEOUT_MS
            val proto = link.protoVersion
            val request = fi.copy().apply { startingPosition = 0; hostPort = fi.hostPort }
            if (!link.send(Msgs.fileInfoToMessage(request, proto, link.isNewGenerationPeer))) throw IOException("cannot send the file request")
            var size = fi.size
            if (proto >= Proto.FILE_TRANSFER_2) {
                val hdrMsg = link.receive()
                if (hdrMsg.type != MessageType.File) throw IOException("invalid file header")
                val hdr = Msgs.fileInfoFrom(hdrMsg, proto) ?: throw IOException("invalid file header")
                size = hdr.size
            }
            var bytes = 0L
            var total = 0L
            FileOutputStream(part).use { out ->
                upsert(TransferInfo(key, peerKey, fi.name, size, 0, false, TransferState.TRANSFERRING, startedAt = started))
                if (size > 0) link.sendBytes(Msgs.bytesArrivedConfirmation(proto, bytes, total))
                while (total < size) {
                    if (canceledKeys.remove(key)) throw IOException("canceled")
                    val chunk = link.receiveBytes()
                    bytes = chunk.size.toLong()
                    total += bytes
                    if (total > size) throw IOException("$total bytes downloaded but the file size is only $size")
                    link.sendBytes(Msgs.bytesArrivedConfirmation(proto, bytes, total))
                    out.write(chunk)
                    onProgress(total)
                    upsert(TransferInfo(key, peerKey, fi.name, size, total, false, TransferState.TRANSFERRING, startedAt = started, bytesPerSecond = transferSpeed(started, total)))
                }
            }
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            fi.lastModified?.let { runCatching { target.setLastModified(it.toEpochMilli()) } }
            upsert(TransferInfo(key, peerKey, fi.name, size, size, false, TransferState.COMPLETED, startedAt = started, localPath = target.absolutePath))
        } finally {
            link?.close()
            if (part.exists()) part.delete()
        }
    }

    // ===== file sharing (BEE-FSHR) ==========================================================================

    fun onShareMessage(peer: Peer, m: BeeMessage, link: BeeLink) {
        if (m.hasFlag(Flag.List)) {
            val files = Msgs.fileShareFrom(m)
            val host = peer.host.ifEmpty { link.peerHostAddress }
            files.forEach { it.hostAddress = host }
            val shared = files.map { SharedFile(peer.key, it) }
            synchronized(this) {
                _remoteShares.value = _remoteShares.value.filter { it.ownerKey != peer.key } + shared
            }
        } else if (m.hasFlag(Flag.Request)) {
            link.send(buildShareList())
        }
    }

    private fun buildShareList(): BeeMessage {
        val infos = _localShares.value.map { registerShared(it) }
        return Msgs.fileShareList(infos, serverPort)
    }

    fun requestShares() {
        val m = Msgs.fileShareRequest()
        for (p in e.peers.value) e.sessionFor(p.key)?.link?.send(m)
    }

    private fun pushShareListToAll() {
        val m = buildShareList()
        for (p in e.peers.value) e.sessionFor(p.key)?.link?.send(m)
    }

    fun addShared(files: List<LocalFile>) {
        synchronized(this) {
            val cur = _localShares.value.toMutableList()
            for (f in files) if (cur.none { it.uri == f.uri }) cur.add(f)
            _localShares.value = cur
        }
        e.host.storage.saveSharedFiles(_localShares.value)
        registerSharedFiles()
        pushShareListToAll()
    }

    fun removeShared(uri: String) {
        synchronized(this) { _localShares.value = _localShares.value.filter { it.uri != uri } }
        sharedIds.remove(uri)?.let { outgoing.remove(it) }
        e.host.storage.saveSharedFiles(_localShares.value)
        pushShareListToAll()
    }

    /** Downloads a file another user shares. */
    fun downloadShared(sf: SharedFile) {
        val fi = sf.info.copy()
        e.pool().execute {
            val key = "s${fi.id}-${System.nanoTime()}"
            try {
                val target = uniqueFile(e.host.downloadDir(), if (fi.shareFolder.isNotEmpty()) safeName(fi.name) else fi.name)
                val o = Offer(0, "", sf.ownerKey, listOf(fi), "", Proto.PROTOCOL_VERSION, false)
                downloadOne(o, fi, target, key) {}
                e.emit(EngineEvent.FileReceived(target.absolutePath, target.name, fi.mimeType))
            } catch (ex: Exception) {
                e.log("Download of shared file ${fi.name} failed: ${ex.message}")
                val cur = _list.value.firstOrNull { it.key == key }
                upsert((cur ?: TransferInfo(key, sf.ownerKey, fi.name, fi.size, 0, false, TransferState.ERROR)).copy(state = TransferState.ERROR, error = ex.message ?: "error"))
            }
        }
    }

    companion object {
        const val BUFFER_SIZE = 65456
        const val TRANSFER_TIMEOUT_MS = 45_000
    }
}
