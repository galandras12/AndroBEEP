package com.galandras12.droidbeep.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.galandras12.droidbeep.core.EngineEvent
import com.galandras12.droidbeep.core.EngineHost
import com.galandras12.droidbeep.core.EngineStorage
import com.galandras12.droidbeep.core.LocalFile
import com.galandras12.droidbeep.core.MsgKind
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Connects the platform independent engine to Android. */
class AndroidHost(
    private val context: Context,
    private val prefs: AppPrefs,
    private val notifier: Notifier,
) : EngineHost {
    override val storage: EngineStorage = JsonStorage(File(context.filesDir, "bee"))
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun openInput(uri: String, skip: Long): InputStream? {
        val ins: InputStream = if (uri.startsWith("content://") || uri.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
        } else FileInputStream(uri)
        var left = skip
        while (left > 0) {
            val n = ins.skip(left)
            if (n <= 0) {
                if (ins.read() < 0) break
                left--
            } else left -= n
        }
        return ins
    }

    override fun listFolder(uri: String, folderName: String): List<LocalFile> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return emptyList()
        val out = ArrayList<LocalFile>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (name.startsWith(".")) continue
                if (f.isDirectory) walk(f, "$rel/$name")
                else if (f.isFile && f.length() > 0) out.add(LocalFile(f.uri.toString(), name, f.length(), f.lastModified(), f.type ?: Media.guessMime(name), rel))
            }
        }
        walk(root, folderName)
        return out
    }

    override fun cacheDir(): File = File(context.cacheDir, "bee").also { it.mkdirs() }

    override fun downloadDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "BeeBeep").also { it.mkdirs() }

    override fun setMulticastLock(enabled: Boolean) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (enabled) {
                if (multicastLock == null) multicastLock = wm.createMulticastLock("beebeep").apply { setReferenceCounted(false) }
                multicastLock?.acquire()
            } else {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun networkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    override fun emit(event: EngineEvent) {
        when (event) {
            is EngineEvent.NewMessage -> {
                if (event.message.kind == MsgKind.BUZZ) return // handled by the Buzz event
                notifier.message(
                    event.chat.id, event.chat.name, event.sender?.displayName ?: "", event.message.text,
                    event.message.kind, event.chat.type != com.galandras12.droidbeep.core.ChatType.PRIVATE,
                )
            }
            is EngineEvent.Buzz -> notifier.buzz(event.chatId, event.from.displayName)
            is EngineEvent.FileOffer -> if (event.message.kind != MsgKind.VOICE) notifier.fileOffer(event.message.chatId, event.from.displayName, event.message.file?.name ?: "")
            is EngineEvent.HelpRequest -> notifier.message(event.chatId, event.from.displayName, event.from.displayName, context.getString(com.galandras12.droidbeep.R.string.help_request_text), MsgKind.HELP, false)
            is EngineEvent.FileReceived -> {
                val f = File(event.localPath)
                val uri = if (prefs.settings.value.saveToDownloads) Media.exportToDownloads(context, f, event.mimeType) else null
                notifier.fileReceived(uri, f, event.mimeType)
            }
            else -> {}
        }
    }

    override fun log(message: String) {
        android.util.Log.d("BeeBeep", message)
    }
}

/** Lets UI code list a picked folder through the same code the engine uses. */
object AndroidHostAccess {
    fun listFolder(context: Context, uri: String, folderName: String): List<LocalFile> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return emptyList()
        val out = ArrayList<LocalFile>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (name.startsWith(".")) continue
                if (f.isDirectory) walk(f, "$rel/$name")
                else if (f.isFile && f.length() > 0) out.add(LocalFile(f.uri.toString(), name, f.length(), f.lastModified(), f.type ?: Media.guessMime(name), rel))
            }
        }
        walk(root, folderName)
        return out
    }
}
