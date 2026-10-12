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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.ui.components.PeerAvatar
import com.galandras12.droidbeep.ui.components.Io
import com.galandras12.droidbeep.ui.components.io
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates a group chat (chatId == null) or edits the name and members of an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(engine: BeeEngine, chatId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val peers by engine.peers.collectAsState()
    val chats by engine.chats.collectAsState()
    val existing = chatId?.let { id -> chats.firstOrNull { it.id == id } }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    val selected = remember(existing?.id) { mutableStateListOf<String>().also { it.addAll(existing?.members ?: emptySet()) } }
    val scope = rememberCoroutineScope()
    val candidates = peers.sortedWith(compareBy({ !it.online }, { it.displayName.lowercase() }))

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (existing == null) R.string.new_group else R.string.edit_group)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = {
                    if (existing == null) {
                        scope.launch {
                            val id = withContext(Io.dispatcher) { engine.createGroup(name, selected.toSet()) }
                            if (id != null) onCreated(id)
                        }
                    } else {
                        scope.io { engine.editGroup(existing.id, name, selected.toSet()) }
                        onBack()
                    }
                }) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.group_name)) }, modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            Text(stringResource(R.string.select_members), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(candidates, key = { it.key }) { p ->
                    val checked = selected.contains(p.key)
                    Row(Modifier.fillMaxWidth().clickable { if (checked) selected.remove(p.key) else selected.add(p.key) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { if (it) selected.add(p.key) else selected.remove(p.key) })
                        Spacer(Modifier.width(8.dp))
                        PeerAvatar(p, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(statusLabel(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
