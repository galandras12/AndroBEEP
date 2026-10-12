package com.galandras12.droidbeep.core

import java.io.File
import java.io.InputStream

/** A message waiting for an offline user. */
data class PendingMessage(
    val peerKey: String,
    val chatId: String,
    val localMessageId: Long,
    val type: String,        // MessageType name
    val wireId: Long,
    val text: String,
    val data: String,
    val flags: Int,
    val timeMillis: Long,
    val voicePath: String? = null,
    val voiceDurationMs: Long = -1,
)

/** Everything the engine persists; implemented with plain files by the Android layer. */
interface EngineStorage {
    fun loadPeers(): List<Peer>
    fun savePeers(peers: List<Peer>)
    fun loadChats(): List<Chat>
    fun saveChats(chats: List<Chat>)
    fun loadMessages(chatId: String): List<ChatMessage>
    fun saveMessages(chatId: String, messages: List<ChatMessage>)
    fun deleteMessages(chatId: String)
    fun loadPending(): List<PendingMessage>
    fun savePending(list: List<PendingMessage>)
    fun loadRefusedChats(): Set<String>
    fun saveRefusedChats(ids: Set<String>)
    fun loadSharedFiles(): List<LocalFile>
    fun saveSharedFiles(files: List<LocalFile>)
}

/** Platform services needed by the engine. */
interface EngineHost {
    val storage: EngineStorage

    /** Opens a local file (content:// uri or path) for reading, skipping [skip] bytes. */
    fun openInput(uri: String, skip: Long): InputStream?

    /** Lists the files of a folder (content tree uri or path) recursively, with [LocalFile.shareFolder] = relative sub-folder. */
    fun listFolder(uri: String, folderName: String): List<LocalFile> = emptyList()

    /** Private cache folder (partial downloads, voice messages). */
    fun cacheDir(): File

    /** Folder where received files are stored. */
    fun downloadDir(): File

    /** Acquire/release the Wi-Fi multicast lock needed to receive UDP broadcasts on Android. */
    fun setMulticastLock(enabled: Boolean) {}

    fun emit(event: EngineEvent)

    fun log(message: String) {}

    /** True if the device has a usable network (Wi-Fi/Ethernet). */
    fun networkAvailable(): Boolean = true
}

/** In-memory storage, used by tests and as the base for simple hosts. */
class MemoryStorage : EngineStorage {
    private var peers: List<Peer> = emptyList()
    private var chats: List<Chat> = emptyList()
    private val messages = HashMap<String, List<ChatMessage>>()
    private var pending: List<PendingMessage> = emptyList()
    private var refused: Set<String> = emptySet()
    private var shared: List<LocalFile> = emptyList()
    override fun loadPeers() = peers
    override fun savePeers(peers: List<Peer>) { this.peers = peers }
    override fun loadChats() = chats
    override fun saveChats(chats: List<Chat>) { this.chats = chats }
    override fun loadMessages(chatId: String) = messages[chatId] ?: emptyList()
    override fun saveMessages(chatId: String, messages: List<ChatMessage>) { this.messages[chatId] = messages }
    override fun deleteMessages(chatId: String) { messages.remove(chatId) }
    override fun loadPending() = pending
    override fun savePending(list: List<PendingMessage>) { pending = list }
    override fun loadRefusedChats() = refused
    override fun saveRefusedChats(ids: Set<String>) { refused = ids }
    override fun loadSharedFiles() = shared
    override fun saveSharedFiles(files: List<LocalFile>) { shared = files }
}
