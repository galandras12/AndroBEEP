package com.galandras12.droidbeep.ui.components

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays one voice message at a time. */
object VoicePlayer {
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var ticker: Job? = null

    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position.asStateFlow()

    fun toggle(path: String) {
        if (_playing.value == path) { stop(); return }
        stop()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.prepare()
            mp.start()
            player = mp
            _playing.value = path
            ticker = scope.launch {
                while (isActive) {
                    _position.value = runCatching { mp.currentPosition }.getOrDefault(0)
                    delay(100)
                }
            }
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playing.value = null
        _position.value = 0
    }
}
