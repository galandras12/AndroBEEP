package com.galandras12.droidbeep.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.Peer
import com.galandras12.droidbeep.core.Proto
import com.galandras12.droidbeep.core.UserStatus
import com.galandras12.droidbeep.ui.components.PeerAvatar

@Composable
fun statusLabel(p: Peer): String = when {
    !p.online -> stringResource(R.string.status_offline)
    p.status == UserStatus.Busy -> stringResource(R.string.status_busy)
    p.status == UserStatus.Away -> stringResource(R.string.status_away)
    else -> stringResource(R.string.status_online)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(engine: BeeEngine, onOpenChat: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val peers by engine.peers.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val filtered = peers.filter {
        query.isBlank() || it.displayName.contains(query, true) || it.fullName.contains(query, true) || it.statusDescription.contains(query, true)
    }.sortedWith(compareBy<Peer>({ !it.online }, { !it.favorite }, { it.displayName.lowercase() }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.search_people)) }
                        )
                    } else Text(stringResource(R.string.people))
                },
                actions = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Default.Close, null) }
                    else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search_people)) }
                        IconButton(onClick = { engine.searchPeers() }) { Icon(Icons.Default.Refresh, stringResource(R.string.search_network)) }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, stringResource(R.string.add_address)) }
        },
    ) { padding ->
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.no_people), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.no_people_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                items(filtered, key = { it.key }) { p ->
                    PeerRow(p, onClick = { onOpenChat(engine.openPrivateChat(p.key)) }, onProfile = { onOpenProfile(p.key) },
                        onFavorite = { engine.toggleFavorite(p.key) }, onRemove = { engine.removeOfflinePeer(p.key) })
                }
            }
        }
    }

    if (showAdd) AddAddressDialog(onDismiss = { showAdd = false }, onAdd = { host, port ->
        engine.connectToAddress(host, port)
        showAdd = false
    })
}

@Composable
private fun PeerRow(p: Peer, onClick: () -> Unit, onProfile: () -> Unit, onFavorite: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PeerAvatar(p)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.favorite) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(0.dp)) }
            }
            Text(
                listOf(statusLabel(p), p.statusDescription.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.profile)) }, onClick = { menu = false; onProfile() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (p.favorite) R.string.remove_favorite else R.string.add_favorite)) },
                    leadingIcon = { Icon(if (p.favorite) Icons.Default.StarBorder else Icons.Default.Star, null) },
                    onClick = { menu = false; onFavorite() })
                if (!p.online) DropdownMenuItem(text = { Text(stringResource(R.string.remove_user)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
fun AddAddressDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Proto.DEFAULT_LISTENER_PORT.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_address)) },
        text = {
            Column {
                Text(stringResource(R.string.add_address_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(6.dp))
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(stringResource(R.string.ip_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true, label = { Text(stringResource(R.string.port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank(), onClick = { onAdd(host.trim(), port.toIntOrNull() ?: Proto.DEFAULT_LISTENER_PORT) }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
