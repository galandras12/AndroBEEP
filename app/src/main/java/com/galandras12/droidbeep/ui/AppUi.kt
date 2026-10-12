package com.galandras12.droidbeep.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.galandras12.droidbeep.BeeApp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.data.Media
import com.galandras12.droidbeep.ui.components.ChatAvatar
import com.galandras12.droidbeep.ui.components.io
import com.galandras12.droidbeep.ui.screens.ChatScreen
import com.galandras12.droidbeep.ui.screens.ChatsScreen
import com.galandras12.droidbeep.ui.screens.GroupScreen
import com.galandras12.droidbeep.ui.screens.LogScreen
import com.galandras12.droidbeep.ui.screens.PeopleScreen
import com.galandras12.droidbeep.ui.screens.PeerScreen
import com.galandras12.droidbeep.ui.screens.ProfileEditScreen
import com.galandras12.droidbeep.ui.screens.SettingsScreen
import com.galandras12.droidbeep.ui.screens.TransfersScreen
import com.galandras12.droidbeep.ui.theme.BeeTheme

@Composable
fun BeeAppUi(app: BeeApp, openChat: String?, sharedUris: List<Uri>, onShareHandled: () -> Unit, onChatOpened: () -> Unit) {
    val settings by app.prefs.settings.collectAsState()
    val engine by app.engineFlow.collectAsState()
    BeeTheme(settings.theme, settings.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val e = engine
            if (!settings.isConfigured || e == null) {
                Welcome(app)
            } else {
                LaunchedEffect(Unit) { app.startService() }
                RequestNotificationPermission()
                MainNav(app, e, openChat, sharedUris, onShareHandled, onChatOpened)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
private fun Welcome(app: BeeApp) {
    var nick by rememberSaveable { mutableStateOf(android.os.Build.MODEL?.take(20) ?: "") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Hexagon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(nick, { nick = it.take(32) }, singleLine = true, label = { Text(stringResource(R.string.nickname)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(enabled = nick.isNotBlank(), onClick = { app.prefs.setNickname(nick) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.get_started)) }
    }
}

private fun chatRoute(id: String) = "chat/" + Uri.encode(id)

@Composable
private fun MainNav(app: BeeApp, engine: BeeEngine, openChat: String?, sharedUris: List<Uri>, onShareHandled: () -> Unit, onChatOpened: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openChat) {
        if (openChat != null) {
            nav.navigate(chatRoute(openChat)) { launchSingleTop = true }
            onChatOpened()
        }
    }
    NavHost(nav, startDestination = "main", modifier = Modifier.fillMaxSize()) {
        composable("main") {
            MainTabs(app, engine, onOpenChat = { nav.navigate(chatRoute(it)) }, onOpenProfile = { nav.navigate("peer/" + Uri.encode(it)) },
                onNewGroup = { nav.navigate("group/new") }, onEditProfile = { nav.navigate("profile/edit") }, onOpenLog = { nav.navigate("log") })
        }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = Uri.decode(entry.arguments?.getString("id") ?: "")
            ChatScreen(engine, id, onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate("peer/" + Uri.encode(it)) },
                onEditGroup = { nav.navigate("group/edit/" + Uri.encode(it)) })
        }
        composable("peer/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = Uri.decode(entry.arguments?.getString("key") ?: "")
            PeerScreen(engine, key, onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(chatRoute(it)) })
        }
        composable("group/new") {
            GroupScreen(engine, null, onBack = { nav.popBackStack() }, onCreated = { id -> nav.popBackStack(); nav.navigate(chatRoute(id)) })
        }
        composable("group/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            GroupScreen(engine, Uri.decode(entry.arguments?.getString("id") ?: ""), onBack = { nav.popBackStack() }, onCreated = {})
        }
        composable("profile/edit") { ProfileEditScreen(app, onBack = { nav.popBackStack() }) }
        composable("log") { LogScreen(engine, onBack = { nav.popBackStack() }) }
    }
    if (sharedUris.isNotEmpty()) SharePicker(engine, sharedUris, onDone = onShareHandled)
}

@Composable
private fun MainTabs(
    app: BeeApp, engine: BeeEngine, onOpenChat: (String) -> Unit, onOpenProfile: (String) -> Unit,
    onNewGroup: () -> Unit, onEditProfile: () -> Unit, onOpenLog: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val chats by engine.chats.collectAsState()
    val unread = chats.sumOf { it.unread }
    val transfers by engine.transferList.collectAsState()
    val active = transfers.count { it.state == com.galandras12.droidbeep.core.TransferState.TRANSFERRING }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.chats)) }, icon = {
                BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) { Icon(Icons.Default.Chat, null) }
            })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.people)) }, icon = { Icon(Icons.Default.Group, null) })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text(stringResource(R.string.files)) }, icon = {
                BadgedBox(badge = { if (active > 0) Badge { Text(active.toString()) } }) { Icon(Icons.Default.FolderOpen, null) }
            })
            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, label = { Text(stringResource(R.string.settings)) }, icon = { Icon(Icons.Default.Settings, null) })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                0 -> ChatsScreen(engine, onOpenChat, onNewGroup)
                1 -> PeopleScreen(engine, onOpenChat, onOpenProfile)
                2 -> TransfersScreen(engine)
                else -> SettingsScreen(app, engine, onEditProfile, onOpenLog)
            }
        }
    }
}

/** Files shared to the app from other apps: choose the chat to send them to. */
@Composable
private fun SharePicker(engine: BeeEngine, uris: List<Uri>, onDone: () -> Unit) {
    val context = LocalContext.current
    val chats by engine.chats.collectAsState()
    val peers by engine.peers.collectAsState()
    val peerMap = peers.associateBy { it.key }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.share_to)) },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                items(chats, key = { it.id }) { c ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable {
                            val files = uris.mapNotNull { Media.describe(context, it) }
                            scope.io { engine.sendFiles(c.id, files) }
                            onDone()
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatAvatar(c, peerMap, size = 40.dp)
                        Spacer(Modifier.padding(6.dp))
                        Text(if (c.type == com.galandras12.droidbeep.core.ChatType.DEFAULT) stringResource(R.string.default_chat_name) else c.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}
