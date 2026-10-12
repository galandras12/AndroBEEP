package com.galandras12.droidbeep.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.galandras12.droidbeep.BeeApp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.Chat
import com.galandras12.droidbeep.core.ChatMessage
import com.galandras12.droidbeep.core.ChatType
import com.galandras12.droidbeep.core.Delivery
import com.galandras12.droidbeep.core.FileRef
import com.galandras12.droidbeep.core.MsgKind
import com.galandras12.droidbeep.core.Peer
import com.galandras12.droidbeep.core.TransferState
import com.galandras12.droidbeep.data.Media
import com.galandras12.droidbeep.data.VoiceRecorder
import com.galandras12.droidbeep.ui.components.ChatAvatar
import com.galandras12.droidbeep.ui.components.VoicePlayer
import com.galandras12.droidbeep.ui.components.dayOf
import com.galandras12.droidbeep.ui.components.formatClock
import com.galandras12.droidbeep.ui.components.formatDay
import com.galandras12.droidbeep.ui.components.formatDuration
import com.galandras12.droidbeep.ui.components.formatSize
import com.galandras12.droidbeep.ui.components.io
import com.galandras12.droidbeep.ui.components.openFile
import com.galandras12.droidbeep.ui.components.openUrl
import com.galandras12.droidbeep.ui.components.parseColor
import com.galandras12.droidbeep.ui.components.splitLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    engine: BeeEngine,
    chatId: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onEditGroup: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BeeApp
    val scope = rememberCoroutineScope()
    val chats by engine.chats.collectAsState()
    val peers by engine.peers.collectAsState()
    val typing by engine.typing.collectAsState()
    val chat = chats.firstOrNull { it.id == chatId }
    val messages by remember(chatId) { engine.messages(chatId) }.collectAsState(initial = emptyList())
    val peerMap = peers.associateBy { it.key }

    // tell the engine which chat is open (no unread counting, read receipts)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chatId, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START || e == Lifecycle.Event.ON_RESUME) {
                engine.setOpenChat(chatId); app.notifier.openChatId = chatId; app.notifier.cancelChat(chatId)
            }
            if (e == Lifecycle.Event.ON_STOP) { engine.setOpenChat(null); app.notifier.openChatId = null }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { engine.setOpenChat(chatId); app.notifier.openChatId = chatId }
        onDispose { lifecycle.removeObserver(obs); engine.setOpenChat(null); app.notifier.openChatId = null }
    }

    if (chat == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val privatePeer = if (chat.type == ChatType.PRIVATE) peerMap[chat.members.firstOrNull()] else null
    val typingNames = typing.filter { it.key.startsWith("$chatId|") && it.value > System.currentTimeMillis() }
        .mapNotNull { peerMap[it.key.substringAfter('|')]?.displayName }

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(typing) { if (typing.isNotEmpty()) { delay(4200); tick++ } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = {
                        if (privatePeer != null) onOpenProfile(privatePeer.key) else if (chat.type == ChatType.GROUP) onEditGroup(chat.id)
                    })) {
                        ChatAvatar(chat, peerMap, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(if (chat.type == ChatType.DEFAULT) stringResource(R.string.default_chat_name) else chat.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                typingNames.isNotEmpty() -> stringResource(R.string.typing, typingNames.joinToString(", "))
                                privatePeer != null -> listOf(statusLabel(privatePeer), privatePeer.statusDescription.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · ")
                                chat.type == ChatType.GROUP -> peerMap.let { m -> (chat.members.mapNotNull { m[it]?.displayName } + stringResource(R.string.you)).joinToString(", ") }
                                else -> stringResource(R.string.online_count, peers.count { it.online })
                            }
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (privatePeer != null) {
                        IconButton(enabled = privatePeer.online, onClick = { scope.io { engine.sendBuzz(privatePeer.key) } }) {
                            Icon(Icons.Default.NotificationsActive, stringResource(R.string.buzz))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (privatePeer != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.profile)) }, onClick = { menu = false; onOpenProfile(privatePeer.key) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.ask_help)) }, enabled = privatePeer.online,
                                    onClick = { menu = false; scope.io { engine.sendHelpRequest(privatePeer.key) } })
                            }
                            if (chat.type == ChatType.GROUP) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit_group)) }, onClick = { menu = false; onEditGroup(chat.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.leave_group)) }, onClick = { menu = false; confirmLeave = true })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.clear_chat)) }, onClick = { menu = false; confirmClear = true })
                        }
                    }
                },
            )
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val queued = messages.count { it.delivery == Delivery.QUEUED }
            if (queued > 0) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queued_banner, queued), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { engine.discardPending(chatId) }) { Text(stringResource(R.string.discard)) }
                    }
                }
            }
            MessageList(engine, chat, messages, peerMap, Modifier.weight(1f))
            if (chat.type == ChatType.GROUP && !chat.localUserIsMember) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.removed_from_group), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else {
                InputBar(engine, chat, scope)
            }
        }
    }

    if (confirmLeave) ConfirmDialog(stringResource(R.string.leave_group), stringResource(R.string.leave_group_confirm), onDismiss = { confirmLeave = false }) {
        confirmLeave = false
        scope.io { engine.leaveGroup(chatId) }
        onBack()
    }
    if (confirmClear) ConfirmDialog(stringResource(R.string.clear_chat), stringResource(R.string.clear_chat_confirm), onDismiss = { confirmClear = false }) {
        confirmClear = false
        engine.clearChat(chatId)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---- message list ---------------------------------------------------------------------------------

@Composable
private fun MessageList(engine: BeeEngine, chat: Chat, messages: List<ChatMessage>, peers: Map<String, Peer>, modifier: Modifier) {
    val state = rememberLazyListState()
    val reversed = remember(messages) { messages.asReversed() }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) state.animateScrollToItem(0) }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_messages), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        items(reversed, key = { it.localId }) { m ->
            val idx = reversed.indexOf(m)
            val older = reversed.getOrNull(idx + 1)
            val showDay = older == null || dayOf(older.time) != dayOf(m.time)
            Column {
                MessageItem(engine, chat, m, peers, older)
                if (showDay) DaySeparator(formatDay(m.time))
            }
        }
    }
}

@Composable
private fun DaySeparator(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(engine: BeeEngine, chat: Chat, m: ChatMessage, peers: Map<String, Peer>, older: ChatMessage?) {
    val context = LocalContext.current
    when (m.kind) {
        MsgKind.SYSTEM -> {
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text(m.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return
        }
        MsgKind.BUZZ, MsgKind.HELP -> {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(50), color = if (m.kind == MsgKind.BUZZ) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (m.kind == MsgKind.BUZZ) Icons.Default.NotificationsActive else Icons.Default.PriorityHigh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(m.text, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            return
        }
        else -> {}
    }

    val mine = m.senderKey == null
    val sender = if (mine) null else peers[m.senderKey]
    val showSender = !mine && chat.type != ChatType.PRIVATE && older?.senderKey != m.senderKey
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)
    var menu by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(top = if (showSender || older == null || (older.senderKey != m.senderKey)) 6.dp else 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Surface(
                shape = shape, color = bubbleColor, contentColor = contentColor,
                modifier = Modifier.widthIn(max = 320.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (showSender) {
                        Text(sender?.displayName ?: "?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = parseColor(sender?.color ?: "#757575").let { c -> if (c.alpha > 0f) c else MaterialTheme.colorScheme.primary }.copy(alpha = 1f).let { adaptiveSenderColor(it) })
                    }
                    when (m.kind) {
                        MsgKind.FILE -> FileCard(engine, m, mine)
                        MsgKind.VOICE -> VoiceCard(engine, m, mine)
                        else -> TextContent(m, contentColor)
                    }
                    Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.important) { Icon(Icons.Default.PriorityHigh, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp)) }
                        Text(formatClock(m.time), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
                        if (mine && m.kind == MsgKind.TEXT) { Spacer(Modifier.width(4.dp)); DeliveryIcon(m.delivery, contentColor) }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                    menu = false
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", m.text))
                })
            }
        }
    }
}

@Composable
private fun adaptiveSenderColor(c: Color): Color {
    // make sure the name colour is readable on the bubble
    val dark = MaterialTheme.colorScheme.surface.let { it.red * 0.3f + it.green * 0.6f + it.blue * 0.1f < 0.5f }
    val lum = c.red * 0.3f + c.green * 0.6f + c.blue * 0.1f
    return when {
        dark && lum < 0.35f -> Color(0xFFFFB950)
        !dark && lum > 0.7f -> Color(0xFF7E5700)
        else -> c
    }
}

@Composable
private fun DeliveryIcon(d: Delivery, tint: Color) {
    val (icon, color) = when (d) {
        Delivery.QUEUED -> Icons.Default.Schedule to tint.copy(alpha = 0.7f)
        Delivery.SENT -> Icons.Default.Check to tint.copy(alpha = 0.7f)
        Delivery.DELIVERED -> Icons.Default.DoneAll to tint.copy(alpha = 0.7f)
        Delivery.READ -> Icons.Default.DoneAll to Color(0xFF1E88E5)
        Delivery.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        Delivery.NONE -> return
    }
    Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
}

@Composable
private fun TextContent(m: ChatMessage, color: Color) {
    val context = LocalContext.current
    if (m.sourceCode) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
            Text(m.text, Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(m.text, linkColor) {
        buildAnnotatedString {
            for (p in splitLinks(m.text)) {
                if (p.url != null) {
                    pushStringAnnotation("URL", p.url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(p.text) }
                    pop()
                } else append(p.text)
            }
        }
    }
    ClickableText(
        annotated, style = MaterialTheme.typography.bodyLarge.copy(color = color),
        onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { openUrl(context, it.item) } }
    )
}

@Composable
private fun fileIcon(mime: String) = when {
    mime.startsWith("image/") -> Icons.Default.Image
    mime.startsWith("audio/") -> Icons.Default.Audiotrack
    mime.startsWith("video/") -> Icons.Default.VideoFile
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun FileCard(engine: BeeEngine, m: ChatMessage, mine: Boolean) {
    val f = m.file ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.widthIn(min = 220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(if (f.folderName.isNotEmpty()) Icons.Default.Folder else fileIcon(f.mimeType), null)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(if (f.folderName.isNotEmpty()) f.folderName else f.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (f.folderName.isNotEmpty()) stringResource(R.string.folder_info, f.fileCount, formatSize(f.size)) else formatSize(f.size),
                    style = MaterialTheme.typography.bodySmall, color = LocalContentColorAlpha()
                )
            }
        }
        val imagePath = f.localPath
        if (f.state == TransferState.COMPLETED && imagePath != null && f.mimeType.startsWith("image/") && File(imagePath).exists()) {
            val bmp = remember(imagePath) {
                runCatching {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(imagePath, o)
                    val sample = (maxOf(o.outWidth, o.outHeight) / 640).coerceAtLeast(1)
                    BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(6.dp))
        when (f.state) {
            TransferState.OFFERED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { scope.io { engine.acceptFile(m.localId) } }) { Text(stringResource(R.string.accept)) }
                OutlinedButton(onClick = { scope.io { engine.declineFile(m.localId) } }) { Text(stringResource(R.string.decline)) }
            }
            TransferState.QUEUED, TransferState.CONNECTING, TransferState.TRANSFERRING -> {
                if (f.size > 0 && f.transferred > 0) {
                    LinearProgressIndicator(progress = { (f.transferred.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatSize(f.transferred)} / ${formatSize(f.size)}", style = MaterialTheme.typography.labelSmall, color = LocalContentColorAlpha())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(if (mine) R.string.waiting_for_download else R.string.connecting), style = MaterialTheme.typography.labelSmall, color = LocalContentColorAlpha())
                }
            }
            TransferState.COMPLETED -> {
                val path = f.localPath
                if (!mine && path != null) TextButton(onClick = { openFile(context, path, f.mimeType) }) { Text(stringResource(R.string.open)) }
                else Text(stringResource(if (mine) R.string.sent else R.string.received), style = MaterialTheme.typography.labelMedium, color = LocalContentColorAlpha())
            }
            TransferState.REFUSED -> Text(stringResource(R.string.refused), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            TransferState.CANCELED -> Text(stringResource(R.string.canceled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            TransferState.ERROR -> Text(stringResource(R.string.transfer_failed) + if (f.error.isNotEmpty()) ": ${f.error}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LocalContentColorAlpha(): Color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f)

@Composable
private fun VoiceCard(engine: BeeEngine, m: ChatMessage, mine: Boolean) {
    val f = m.file ?: return
    val playing by VoicePlayer.playing.collectAsState()
    val position by VoicePlayer.position.collectAsState()
    val path = f.localPath
    val isPlaying = path != null && playing == path
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 200.dp)) {
        FilledIconButton(onClick = { if (path != null) VoicePlayer.toggle(path) }, enabled = path != null && File(path).exists(), modifier = Modifier.size(40.dp)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val total = if (f.durationMs > 0) f.durationMs else 0L
            if (isPlaying && total > 0) LinearProgressIndicator(progress = { (position.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { if (f.state == TransferState.COMPLETED) 1f else if (f.size > 0) f.transferred.toFloat() / f.size else 0f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    if (total > 0) formatDuration(if (isPlaying) position.toLong() else total) else stringResource(R.string.voice_message),
                    style = MaterialTheme.typography.labelSmall, color = LocalContentColorAlpha()
                )
                Spacer(Modifier.weight(1f))
                when (f.state) {
                    TransferState.ERROR -> Text(stringResource(R.string.transfer_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    TransferState.QUEUED, TransferState.CONNECTING, TransferState.TRANSFERRING -> Text(stringResource(R.string.loading), style = MaterialTheme.typography.labelSmall, color = LocalContentColorAlpha())
                    TransferState.OFFERED -> {
                        val scope = rememberCoroutineScope()
                        TextButton(onClick = { scope.io { engine.acceptFile(m.localId) } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.download)) }
                    }
                    else -> {}
                }
            }
        }
    }
}

// ---- input bar ------------------------------------------------------------------------------------

@Composable
private fun InputBar(engine: BeeEngine, chat: Chat, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var text by remember(chat.id) { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var important by remember { mutableStateOf(false) }
    var asCode by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recSeconds by remember { mutableStateOf(0) }
    var lastTyping by remember { mutableStateOf(0L) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.mapNotNull { Media.describe(context, it) }
        if (files.isNotEmpty()) scope.io { engine.sendFiles(chat.id, files) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "folder"
            scope.io { engine.sendFolder(chat.id, uri.toString(), name) }
        }
    }
    fun startRecording() {
        val file = File(context.cacheDir, "bee/voice").also { it.mkdirs() }.let { File(it, "voice_${System.currentTimeMillis()}.wav") }
        val r = VoiceRecorder(file)
        if (r.start()) { recorder = r; recSeconds = 0 }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }

    LaunchedEffect(recorder) {
        while (recorder != null) { delay(1000); recSeconds++ }
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        val rec = recorder
        if (rec != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { rec.cancel(); recorder = null }) { Icon(Icons.Default.Close, stringResource(R.string.cancel), tint = MaterialTheme.colorScheme.error) }
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.recording) + "  " + formatDuration(recSeconds * 1000L), modifier = Modifier.weight(1f))
                FilledIconButton(onClick = {
                    val ok = rec.stop()
                    recorder = null
                    if (ok) scope.io { engine.sendVoice(chat.id, rec.outFile.absolutePath, rec.durationMs) }
                }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.send_files)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = { attachMenu = false; filePicker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.send_folder)) }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { attachMenu = false; folderPicker.launch(null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.important_message)) }, leadingIcon = { Checkbox(important, null) },
                            onClick = { important = !important })
                        DropdownMenuItem(text = { Text(stringResource(R.string.source_code)) }, leadingIcon = { Checkbox(asCode, null) },
                            onClick = { asCode = !asCode })
                    }
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        val now = System.currentTimeMillis()
                        if (it.isNotEmpty() && now - lastTyping > 3000) { lastTyping = now; scope.io { engine.sendTyping(chat.id) } }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(if (important) R.string.write_important else R.string.write_message)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                )
                Spacer(Modifier.width(6.dp))
                if (text.isBlank()) {
                    FilledIconButton(onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Default.Mic, stringResource(R.string.record_voice)) }
                } else {
                    FilledIconButton(onClick = {
                        val t = text; val imp = important; val code = asCode
                        text = ""; important = false; asCode = false
                        scope.io { engine.sendMessage(chat.id, t, imp, code) }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
                }
            }
        }
    }
}
