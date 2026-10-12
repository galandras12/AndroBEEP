package com.galandras12.droidbeep.core

import java.time.Instant

/** A remote BeeBEEP user (online or remembered while offline). */
data class Peer(
    val key: String,                     // stable identity: account@domain (lower case)
    val name: String,
    val account: String,
    val domain: String,
    val hash: String = "",
    val status: Int = UserStatus.Offline,
    val statusDescription: String = "",
    val color: String = "#757575",
    val version: String = "",
    val qtVersion: String = "",
    val host: String = "",
    val port: Int = Proto.DEFAULT_LISTENER_PORT,
    val localHostName: String = "",
    val protocolVersion: Int = 0,
    val online: Boolean = false,
    val favorite: Boolean = false,
    val lastConnection: Instant? = null,
    val isHelper: Boolean = false,
    val vcard: VCard = VCard(),
    val encrypted: Boolean = true,
) {
    val displayName: String get() = name.ifEmpty { account }
    val fullName: String get() = listOf(vcard.firstName, vcard.lastName).filter { it.isNotBlank() }.joinToString(" ")
}

data class VCard(
    val nickName: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val birthday: String = "",   // yyyy-MM-dd
    val email: String = "",
    val phone: String = "",
    val roomLocation: String = "",
    val info: String = "",
    val photoPngBase64: String = "",
) {
    val hasOnlyNickName: Boolean
        get() = firstName.isEmpty() && lastName.isEmpty() && birthday.isEmpty() && email.isEmpty() &&
            phone.isEmpty() && roomLocation.isEmpty() && info.isEmpty() && photoPngBase64.isEmpty()
}

enum class ChatType { DEFAULT, PRIVATE, GROUP }

data class Chat(
    val id: String,
    val type: ChatType,
    val name: String,
    val privateId: String,
    val members: Set<String> = emptySet(),   // peer keys, the local user is implicit
    val lastModified: Instant? = null,
    val unread: Int = 0,
    val lastActivity: Instant = Instant.EPOCH,
    val lastPreview: String = "",
    val readByOthers: Set<String> = emptySet(),
    val localUserIsMember: Boolean = true,
)

enum class MsgKind { TEXT, SYSTEM, FILE, VOICE, BUZZ, HELP }

enum class Delivery { NONE, QUEUED, SENT, DELIVERED, READ, FAILED }

enum class TransferState { OFFERED, QUEUED, CONNECTING, TRANSFERRING, COMPLETED, REFUSED, CANCELED, ERROR }

data class FileRef(
    val fileId: Long,
    val name: String,
    val size: Long,
    val state: TransferState,
    val upload: Boolean,
    val transferred: Long = 0,
    val localPath: String? = null,    // downloaded file or cached source
    val mimeType: String = "",
    val durationMs: Long = -1,
    val error: String = "",
    val folderName: String = "",
    val fileCount: Int = 1,
)

data class ChatMessage(
    val localId: Long,
    val chatId: String,
    val senderKey: String?,           // null = the local user
    val text: String,
    val time: Instant,
    val kind: MsgKind = MsgKind.TEXT,
    val delivery: Delivery = Delivery.NONE,
    val wireId: Long = 0,
    val important: Boolean = false,
    val sourceCode: Boolean = false,
    val auto: Boolean = false,
    val file: FileRef? = null,
)

/** Info about a file shared on the network (BEE-FSHR lists). */
data class SharedFile(
    val ownerKey: String,
    val info: BeeFileInfo,
)

class BeeFileInfo(
    var id: Long = 0,
    var name: String = "",
    var size: Long = 0,
    var password: String = "",
    var fileHash: String = "",
    var shareFolder: String = "",
    var inShareBox: Boolean = false,
    var chatPrivateId: String = "",
    var lastModified: Instant? = null,
    var mimeType: String = "",
    var contentType: Int = 0,          // 0 = file, 1 = voice message
    var startingPosition: Long = 0,
    var duration: Long = -1,
    var hostPort: Int = 0,
    var hostAddress: String = "",
) {
    val suffix: String
        get() = name.substringAfterLast('.', "")
    val isVoiceMessage: Boolean get() = contentType == 1
    fun copy(): BeeFileInfo = BeeFileInfo(
        id, name, size, password, fileHash, shareFolder, inShareBox, chatPrivateId, lastModified,
        mimeType, contentType, startingPosition, duration, hostPort, hostAddress
    )
}

/** A file or voice message the local user offers to others. */
class LocalFile(
    val uri: String,           // content:// uri or absolute path
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val shareFolder: String = "",
)

data class TransferInfo(
    val key: String,
    val peerKey: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val upload: Boolean,
    val state: TransferState,
    val error: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val bytesPerSecond: Long = 0,
    val localPath: String? = null,
)

/** Notifications for the Android layer (sound, vibration, system notifications). */
sealed interface EngineEvent {
    data class NewMessage(val chat: Chat, val message: ChatMessage, val sender: Peer?) : EngineEvent
    data class Buzz(val from: Peer, val chatId: String) : EngineEvent
    data class FileOffer(val from: Peer, val message: ChatMessage) : EngineEvent
    data class HelpRequest(val from: Peer, val chatId: String) : EngineEvent
    data class PeerConnected(val peer: Peer) : EngineEvent
    data class PeerDisconnected(val peer: Peer) : EngineEvent
    data class FileReceived(val localPath: String, val name: String, val mimeType: String) : EngineEvent
    data class Info(val text: String) : EngineEvent
}

/** Settings the engine itself needs; owned and persisted by the Android layer. */
data class EngineConfig(
    val nickname: String,
    val accountName: String,
    val domainName: String,
    val localHostName: String,
    val userHash: String,
    val color: String = "#2E7D32",
    val networkPassword: String = "",
    val listenerPort: Int = Proto.DEFAULT_LISTENER_PORT,
    val fileTransferPort: Int = Proto.DEFAULT_FILE_TRANSFER_PORT,
    val broadcastPort: Int = Proto.DEFAULT_BROADCAST_PORT,
    val useMulticast: Boolean = true,
    val manualAddresses: List<String> = emptyList(),   // "host" or "host:port"
    val autoAcceptVoiceMessages: Boolean = true,
    val acceptHelpRequests: Boolean = false,
    val workgroups: List<String> = emptyList(),
    val sendFilesToEveryoneInDefaultChat: Boolean = true,
)
