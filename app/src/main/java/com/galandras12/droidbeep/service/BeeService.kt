package com.galandras12.droidbeep.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.galandras12.droidbeep.BeeApp
import com.galandras12.droidbeep.core.BeeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the BeeBEEP engine alive in the background (foreground service) so that messages arrive. */
class BeeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BeeApp
        if (intent?.action == ACTION_STOP) {
            app.engine?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = app.notifier.serviceNotification(app.engine?.peers?.value?.count { it.online } ?: 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(NOTIF_ID, notification)
        if (!started) {
            started = true
            scope.launch {
                app.engineFlow.collectLatest { engine ->
                    if (engine == null) return@collectLatest
                    engine.start()
                    registerNetworkCallback(engine)
                    engine.peers.collect { peers ->
                        val n = app.notifier.serviceNotification(peers.count { it.online })
                        runCatching { NotificationManagerCompat.from(this@BeeService).notify(NOTIF_ID, n) }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun registerNetworkCallback(engine: BeeEngine) {
        if (callback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { engine.onNetworkChanged() }
            override fun onLost(network: Network) { engine.onNetworkChanged() }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    override fun onDestroy() {
        callback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        callback = null
        (application as BeeApp).engine?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.galandras12.droidbeep.STOP"
        const val NOTIF_ID = 1
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as BeeApp
            if (app.prefs.settings.value.startOnBoot) app.startService()
        }
    }
}
