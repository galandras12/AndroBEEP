package com.galandras12.droidbeep.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.galandras12.droidbeep.MainActivity
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.MsgKind
import java.io.File

/** System notifications, sounds and vibration. */
class Notifier(private val context: Context, private val prefs: AppPrefs) {
    @Volatile var foreground = false
    @Volatile var openChatId: String? = null

    init { createChannels() }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_BUZZ, context.getString(R.string.channel_buzz), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 600)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build())
        })
        nm.createNotificationChannel(NotificationChannel(CH_FILES, context.getString(R.string.channel_files), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(chatId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (chatId != null) putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getActivity(context, (chatId ?: "").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun serviceNotification(online: Int): android.app.Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bee)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.users_online, online, online))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent(null))
            .addAction(0, context.getString(R.string.action_stop), PendingIntent.getService(
                context, 1, Intent(context, com.galandras12.droidbeep.service.BeeService::class.java).setAction(com.galandras12.droidbeep.service.BeeService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    fun message(chatId: String, chatName: String, sender: String, text: String, kind: MsgKind, group: Boolean) {
        val s = prefs.settings.value
        if (!s.notifyMessages && kind != MsgKind.HELP) return
        if (foreground && openChatId == chatId) return
        if (!canNotify()) return
        val title = if (group) "$chatName · $sender" else sender
        val body = when (kind) {
            MsgKind.FILE -> "📎 $text"
            MsgKind.VOICE -> "🎤 " + context.getString(R.string.voice_message)
            else -> text
        }
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_bee)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(chatId))
            .setSilent(!s.sound && !s.vibrate)
            .build()
        NotificationManagerCompat.from(context).notify(chatId.hashCode(), n)
        if (foreground) vibrateShort()
    }

    fun buzz(chatId: String, sender: String) {
        val s = prefs.settings.value
        vibrate(longArrayOf(0, 400, 150, 400, 150, 600))
        if (!s.notifyBuzz || !canNotify()) return
        val n = NotificationCompat.Builder(context, CH_BUZZ)
            .setSmallIcon(R.drawable.ic_stat_bee)
            .setContentTitle(context.getString(R.string.buzz_title, sender))
            .setContentText(context.getString(R.string.buzz_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent(chatId))
            .build()
        NotificationManagerCompat.from(context).notify(("buzz$chatId").hashCode(), n)
        if (s.sound && foreground) runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    fun fileOffer(chatId: String, sender: String, name: String) {
        if (!prefs.settings.value.notifyFiles || !canNotify()) return
        if (foreground && openChatId == chatId) return
        val n = NotificationCompat.Builder(context, CH_FILES)
            .setSmallIcon(R.drawable.ic_stat_bee)
            .setContentTitle(context.getString(R.string.file_offer_title, sender))
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openIntent(chatId))
            .build()
        NotificationManagerCompat.from(context).notify(("file$chatId").hashCode(), n)
    }

    fun fileReceived(uri: Uri?, file: File, mime: String) {
        if (!prefs.settings.value.notifyFiles || !canNotify()) return
        val viewUri = uri ?: FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(viewUri, mime.ifEmpty { Media.guessMime(file.name) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, file.name.hashCode(), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CH_FILES)
            .setSmallIcon(R.drawable.ic_stat_bee)
            .setContentTitle(context.getString(R.string.file_received))
            .setContentText(file.name)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(("recv" + file.name).hashCode(), n)
    }

    fun cancelChat(chatId: String) {
        NotificationManagerCompat.from(context).cancel(chatId.hashCode())
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun vibrateShort() {
        if (prefs.settings.value.vibrate) vibrate(longArrayOf(0, 60))
    }

    private fun vibrate(pattern: LongArray) {
        runCatching {
            val v = vibrator() ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        }
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MESSAGES = "messages"
        const val CH_BUZZ = "buzz"
        const val CH_FILES = "files"
    }
}
