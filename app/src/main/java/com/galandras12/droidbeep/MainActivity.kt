package com.galandras12.droidbeep

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.galandras12.droidbeep.ui.BeeAppUi

class MainActivity : ComponentActivity() {
    private val openChat = mutableStateOf<String?>(null)
    private val shared = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val app = application as BeeApp
        setContent {
            BeeAppUi(app, openChat.value, shared.value, onShareHandled = { shared.value = emptyList() }, onChatOpened = { openChat.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CHAT_ID)?.let { openChat.value = it }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) shared.value = listOf(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!list.isNullOrEmpty()) shared.value = list
            }
        }
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
    }
}
