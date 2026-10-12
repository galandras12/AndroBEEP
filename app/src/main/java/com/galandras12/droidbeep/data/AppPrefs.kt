package com.galandras12.droidbeep.data

import android.content.Context
import android.os.Build
import com.galandras12.droidbeep.core.Crypto
import com.galandras12.droidbeep.core.EngineConfig
import com.galandras12.droidbeep.core.Proto
import com.galandras12.droidbeep.core.UserStatus
import com.galandras12.droidbeep.core.VCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val nickname: String = "",
    val accountName: String = "",
    val domainName: String = "",
    val hostName: String = "",
    val userHash: String = "",
    val color: String = "#F9A825",
    val status: Int = UserStatus.Online,
    val statusDescription: String = "",
    val networkPassword: String = "",
    val listenerPort: Int = Proto.DEFAULT_LISTENER_PORT,
    val filePort: Int = Proto.DEFAULT_FILE_TRANSFER_PORT,
    val broadcastPort: Int = Proto.DEFAULT_BROADCAST_PORT,
    val useMulticast: Boolean = true,
    val manualAddresses: List<String> = emptyList(),
    val autoAcceptVoice: Boolean = true,
    val acceptHelp: Boolean = false,
    val notifyMessages: Boolean = true,
    val notifyBuzz: Boolean = true,
    val notifyFiles: Boolean = true,
    val vibrate: Boolean = true,
    val sound: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val startOnBoot: Boolean = false,
    val saveToDownloads: Boolean = true,
    val vcard: VCard = VCard(),
    val workgroups: List<String> = emptyList(),
) {
    val isConfigured: Boolean get() = nickname.isNotBlank()

    fun toEngineConfig() = EngineConfig(
        nickname = nickname, accountName = accountName, domainName = domainName, localHostName = hostName, userHash = userHash,
        color = color, networkPassword = networkPassword, listenerPort = listenerPort, fileTransferPort = filePort,
        broadcastPort = broadcastPort, useMulticast = useMulticast, manualAddresses = manualAddresses,
        autoAcceptVoiceMessages = autoAcceptVoice, acceptHelpRequests = acceptHelp, workgroups = workgroups,
    )
}

/** SharedPreferences backed settings, exposed as a StateFlow for the UI. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("beebeep_droid", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val hash = sp.getString("hash", null) ?: Crypto.simpleHash(UUID.randomUUID().toString() + System.nanoTime()).also { sp.edit().putString("hash", it).apply() }
        var domain = sp.getString("domain", null)
        if (domain == null) {
            domain = sanitize(Build.MODEL).ifEmpty { "android" } + "-" + hash.take(4)
            sp.edit().putString("domain", domain).apply()
        }
        return AppSettings(
            nickname = sp.getString("nickname", "") ?: "",
            accountName = sp.getString("account", "") ?: "",
            domainName = domain,
            hostName = Build.MODEL ?: "Android",
            userHash = hash,
            color = sp.getString("color", "#F9A825") ?: "#F9A825",
            status = sp.getInt("status", UserStatus.Online),
            statusDescription = sp.getString("statusDesc", "") ?: "",
            networkPassword = sp.getString("password", "") ?: "",
            listenerPort = sp.getInt("listenerPort", Proto.DEFAULT_LISTENER_PORT),
            filePort = sp.getInt("filePort", Proto.DEFAULT_FILE_TRANSFER_PORT),
            broadcastPort = sp.getInt("broadcastPort", Proto.DEFAULT_BROADCAST_PORT),
            useMulticast = sp.getBoolean("multicast", true),
            manualAddresses = (sp.getString("manual", "") ?: "").split('\n').filter { it.isNotBlank() },
            autoAcceptVoice = sp.getBoolean("autoVoice", true),
            acceptHelp = sp.getBoolean("acceptHelp", false),
            notifyMessages = sp.getBoolean("notifyMessages", true),
            notifyBuzz = sp.getBoolean("notifyBuzz", true),
            notifyFiles = sp.getBoolean("notifyFiles", true),
            vibrate = sp.getBoolean("vibrate", true),
            sound = sp.getBoolean("sound", true),
            theme = runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = sp.getBoolean("dynamicColor", true),
            startOnBoot = sp.getBoolean("startOnBoot", false),
            saveToDownloads = sp.getBoolean("saveToDownloads", true),
            vcard = VCard(
                sp.getString("vc_nick", "") ?: "", sp.getString("vc_first", "") ?: "", sp.getString("vc_last", "") ?: "",
                sp.getString("vc_birthday", "") ?: "", sp.getString("vc_email", "") ?: "", sp.getString("vc_phone", "") ?: "",
                sp.getString("vc_room", "") ?: "", sp.getString("vc_info", "") ?: "", sp.getString("vc_photo", "") ?: "",
            ),
            workgroups = (sp.getString("workgroups", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        sp.edit()
            .putString("nickname", s.nickname).putString("account", s.accountName).putString("color", s.color)
            .putInt("status", s.status).putString("statusDesc", s.statusDescription).putString("password", s.networkPassword)
            .putInt("listenerPort", s.listenerPort).putInt("filePort", s.filePort).putInt("broadcastPort", s.broadcastPort)
            .putBoolean("multicast", s.useMulticast).putString("manual", s.manualAddresses.joinToString("\n"))
            .putBoolean("autoVoice", s.autoAcceptVoice).putBoolean("acceptHelp", s.acceptHelp)
            .putBoolean("notifyMessages", s.notifyMessages).putBoolean("notifyBuzz", s.notifyBuzz).putBoolean("notifyFiles", s.notifyFiles)
            .putBoolean("vibrate", s.vibrate).putBoolean("sound", s.sound)
            .putString("theme", s.theme.name).putBoolean("dynamicColor", s.dynamicColor).putBoolean("startOnBoot", s.startOnBoot)
            .putBoolean("saveToDownloads", s.saveToDownloads)
            .putString("vc_nick", s.vcard.nickName).putString("vc_first", s.vcard.firstName).putString("vc_last", s.vcard.lastName)
            .putString("vc_birthday", s.vcard.birthday).putString("vc_email", s.vcard.email).putString("vc_phone", s.vcard.phone)
            .putString("vc_room", s.vcard.roomLocation).putString("vc_info", s.vcard.info).putString("vc_photo", s.vcard.photoPngBase64)
            .putString("workgroups", s.workgroups.joinToString(","))
            .apply()
    }

    /** First start: choose the nickname, the account name stays the same forever. */
    fun setNickname(nickname: String) {
        update {
            val acc = if (it.accountName.isEmpty()) sanitize(nickname).ifEmpty { "android" } else it.accountName
            it.copy(nickname = nickname.trim(), accountName = acc)
        }
    }

    companion object {
        fun sanitize(s: String?): String = (s ?: "").lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24)
    }
}
