package com.galandras12.droidbeep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.ui.components.PeerAvatar
import com.galandras12.droidbeep.ui.components.io

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerScreen(engine: BeeEngine, peerKey: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val peers by engine.peers.collectAsState()
    val peer = peers.firstOrNull { it.key == peerKey }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.profile)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (peer != null) IconButton(onClick = { engine.toggleFavorite(peer.key) }) {
                    Icon(if (peer.favorite) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.add_favorite))
                }
            }
        )
    }) { padding ->
        if (peer == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PeerAvatar(peer, size = 96.dp)
            Spacer(Modifier.height(12.dp))
            Text(peer.displayName, style = MaterialTheme.typography.headlineSmall)
            if (peer.fullName.isNotEmpty()) Text(peer.fullName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOf(statusLabel(peer), peer.statusDescription.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onOpenChat(engine.openPrivateChat(peer.key)) }) {
                    Icon(Icons.Default.Chat, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.message))
                }
                OutlinedButton(enabled = peer.online, onClick = { scope.io { engine.sendBuzz(peer.key) } }) {
                    Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.buzz))
                }
            }
            Spacer(Modifier.height(20.dp))
            val vc = peer.vcard
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Info(R.string.nickname, peer.name)
                    Info(R.string.first_name, vc.firstName)
                    Info(R.string.last_name, vc.lastName)
                    Info(R.string.birthday, vc.birthday)
                    Info(R.string.email, vc.email)
                    Info(R.string.phone, vc.phone)
                    Info(R.string.room, vc.roomLocation)
                    Info(R.string.info, vc.info)
                }
            }
            Spacer(Modifier.height(12.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Info(R.string.account, peer.account)
                    Info(R.string.domain, peer.domain)
                    Info(R.string.host_name, peer.localHostName)
                    Info(R.string.address, if (peer.host.isNotEmpty()) "${peer.host}:${peer.port}" else "")
                    Info(R.string.version, listOf(peer.version, peer.qtVersion.takeIf { it.isNotEmpty() }?.let { "Qt$it" }, peer.protocolVersion.takeIf { it > 0 }?.let { "p$it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "))
                    if (peer.online) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(if (peer.encrypted) R.string.encrypted_connection else R.string.plain_connection), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Info(label: Int, value: String) {
    if (value.isBlank()) return
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
