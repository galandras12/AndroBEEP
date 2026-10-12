package com.galandras12.droidbeep.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.Chat
import com.galandras12.droidbeep.core.ChatType
import com.galandras12.droidbeep.ui.components.ChatAvatar
import com.galandras12.droidbeep.ui.components.formatListTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(engine: BeeEngine, onOpenChat: (String) -> Unit, onNewGroup: () -> Unit) {
    val chats by engine.chats.collectAsState()
    val peers by engine.peers.collectAsState()
    val running by engine.running.collectAsState()
    val peerMap = peers.associateBy { it.key }
    val visible = chats.filter { c ->
        c.type == ChatType.DEFAULT || c.type == ChatType.GROUP || c.lastPreview.isNotEmpty() || (peerMap[c.members.firstOrNull()]?.online == true)
    }
    val online = peers.count { it.online }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (running) pluralStringResourceCompat(R.plurals.users_online, online) else stringResource(R.string.offline),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewGroup) { Icon(Icons.Default.GroupAdd, stringResource(R.string.new_group)) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewGroup, icon = { Icon(Icons.Default.GroupAdd, null) }, text = { Text(stringResource(R.string.new_group)) })
        },
    ) { padding ->
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_chats), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(visible, key = { it.id }) { chat -> ChatRow(chat, peerMap, onOpenChat) }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, peers: Map<String, com.galandras12.droidbeep.core.Peer>, onOpen: (String) -> Unit) {
    val title = if (chat.type == ChatType.DEFAULT) stringResource(R.string.default_chat_name) else chat.name
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(chat.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatAvatar(chat, peers)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Medium)
                Text(formatListTime(chat.lastActivity), style = MaterialTheme.typography.labelSmall,
                    color = if (chat.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastPreview.ifEmpty { if (chat.type == ChatType.GROUP) stringResource(R.string.members_count, chat.members.size + 1) else "" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (chat.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(chat.unread.toString()) }
                }
            }
        }
    }
}

@Composable
fun pluralStringResourceCompat(id: Int, count: Int): String =
    androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(id, count, count)
