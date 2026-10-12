package com.galandras12.droidbeep

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.data.AndroidHost
import com.galandras12.droidbeep.data.AppPrefs
import com.galandras12.droidbeep.data.AppSettings
import com.galandras12.droidbeep.data.Notifier
import com.galandras12.droidbeep.service.BeeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BeeApp : Application() {
    lateinit var prefs: AppPrefs
        private set
    lateinit var notifier: Notifier
        private set
    private lateinit var host: AndroidHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engine = MutableStateFlow<BeeEngine?>(null)
    /** The engine, available as soon as the user has chosen a nickname. */
    val engineFlow: StateFlow<BeeEngine?> = _engine.asStateFlow()
    val engine: BeeEngine? get() = _engine.value

    private var appliedConfig: AppSettings? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        notifier = Notifier(this, prefs)
        host = AndroidHost(this, prefs, notifier)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { notifier.foreground = true }
            override fun onStop(owner: LifecycleOwner) { notifier.foreground = false }
        })
        scope.launch {
            prefs.settings.collect { applySettings(it) }
        }
    }

    @Synchronized
    private fun applySettings(s: AppSettings) {
        if (!s.isConfigured) return
        val old = appliedConfig
        appliedConfig = s
        var e = _engine.value
        if (e == null) {
            e = BeeEngine(host, s.toEngineConfig())
            e.setStatusSilently(s)
            _engine.value = e
            return
        }
        val networkChanged = old == null || old.nickname != s.nickname || old.networkPassword != s.networkPassword ||
            old.listenerPort != s.listenerPort || old.filePort != s.filePort || old.broadcastPort != s.broadcastPort ||
            old.useMulticast != s.useMulticast || old.userHash != s.userHash || old.workgroups != s.workgroups
        if (networkChanged && e.running.value) e.restart(s.toEngineConfig()) else e.updateConfigLive(s.toEngineConfig())
        if (old == null || old.status != s.status || old.statusDescription != s.statusDescription) e.setStatus(s.status, s.statusDescription)
        if (old == null || old.vcard != s.vcard || old.color != s.color || old.acceptHelp != s.acceptHelp) e.setVCard(s.vcard)
        if (old != null && (old.manualAddresses != s.manualAddresses)) e.searchPeers()
    }

    private fun BeeEngine.setStatusSilently(s: AppSettings) {
        setStatus(s.status, s.statusDescription)
        setVCard(s.vcard)
    }

    /** Starts the foreground service which runs the engine. */
    fun startService() {
        if (!prefs.settings.value.isConfigured) return
        ContextCompat.startForegroundService(this, Intent(this, BeeService::class.java))
    }

    fun stopService() {
        startService(Intent(this, BeeService::class.java).setAction(BeeService.ACTION_STOP))
    }

    companion object {
        lateinit var instance: BeeApp
            private set
    }
}

val Context.beeApp: BeeApp get() = applicationContext as BeeApp
