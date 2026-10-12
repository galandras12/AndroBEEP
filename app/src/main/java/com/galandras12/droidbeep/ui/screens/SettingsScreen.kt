package com.galandras12.droidbeep.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.BeeApp
import com.galandras12.droidbeep.BuildConfig
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.Discovery
import com.galandras12.droidbeep.core.UserStatus
import com.galandras12.droidbeep.core.VCard
import com.galandras12.droidbeep.data.AppSettings
import com.galandras12.droidbeep.data.ThemeMode
import com.galandras12.droidbeep.ui.components.PeerAvatar
import com.galandras12.droidbeep.ui.components.colorToHex
import com.galandras12.droidbeep.ui.components.decodePhoto
import com.galandras12.droidbeep.ui.components.encodePhoto
import com.galandras12.droidbeep.ui.components.parseColor
import com.galandras12.droidbeep.ui.components.scalePhoto

private val PALETTE = listOf("#F9A825", "#EF6C00", "#D84315", "#C2185B", "#7B1FA2", "#512DA8", "#1565C0", "#0277BD", "#00838F", "#00695C", "#2E7D32", "#558B2F", "#6D4C41", "#455A64")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: BeeApp, engine: BeeEngine, onEditProfile: () -> Unit, onOpenLog: () -> Unit) {
    val s by app.prefs.settings.collectAsState()
    val running by engine.running.collectAsState()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // ---- profile card
            Row(Modifier.fillMaxWidth().clickable(onClick = onEditProfile).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val photo = remember(s.vcard.photoPngBase64) { decodePhoto(s.vcard.photoPngBase64) }
                if (photo != null) androidx.compose.foundation.Image(photo, null, Modifier.size(64.dp).clip(CircleShape))
                else Box(Modifier.size(64.dp).clip(CircleShape).background(parseColor(s.color)), contentAlignment = Alignment.Center) {
                    Text(s.nickname.firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.nickname, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (running) stringResource(R.string.connected_as, s.accountName + "@" + s.domainName) else stringResource(R.string.offline),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onEditProfile) { Text(stringResource(R.string.edit)) }
            }

            Section(R.string.status)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UserStatus.Online to R.string.status_online, UserStatus.Busy to R.string.status_busy, UserStatus.Away to R.string.status_away).forEach { (st, label) ->
                    FilterChip(selected = s.status == st, onClick = { app.prefs.update { it.copy(status = st) } }, label = { Text(stringResource(label)) })
                }
            }
            ClickRow(R.string.status_description, s.statusDescription.ifEmpty { stringResource(R.string.not_set) }) { dialog = "statusDesc" }

            Section(R.string.my_color)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { hex ->
                    val c = parseColor(hex)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(c)
                            .border(if (s.color.equals(hex, true)) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                            .clickable { app.prefs.update { it.copy(color = hex) } },
                        contentAlignment = Alignment.Center
                    ) { if (s.color.equals(hex, true)) Icon(Icons.Default.Check, null, tint = Color.White) }
                }
            }

            Section(R.string.network)
            ClickRow(R.string.nickname, s.nickname, R.string.nickname_hint) { dialog = "nickname" }
            ClickRow(R.string.network_password, if (s.networkPassword.isEmpty()) stringResource(R.string.default_password) else "••••••••", R.string.network_password_hint) { dialog = "password" }
            ClickRow(R.string.listener_port, s.listenerPort.toString()) { dialog = "listener" }
            ClickRow(R.string.file_port, s.filePort.toString()) { dialog = "file" }
            SwitchRow(R.string.use_multicast, R.string.use_multicast_hint, s.useMulticast) { v -> app.prefs.update { it.copy(useMulticast = v) } }
            ClickRow(R.string.manual_addresses, if (s.manualAddresses.isEmpty()) stringResource(R.string.not_set) else s.manualAddresses.joinToString(", "), R.string.manual_addresses_hint) { dialog = "manual" }
            ClickRow(R.string.my_addresses, remember { Discovery.localAddresses().filter { it != "127.0.0.1" }.joinToString(", ") }.ifEmpty { "-" })
            ClickRow(R.string.connection_log, stringResource(R.string.connection_log_hint)) { onOpenLog() }

            Section(R.string.notifications)
            SwitchRow(R.string.notify_messages, null, s.notifyMessages) { v -> app.prefs.update { it.copy(notifyMessages = v) } }
            SwitchRow(R.string.notify_buzz, null, s.notifyBuzz) { v -> app.prefs.update { it.copy(notifyBuzz = v) } }
            SwitchRow(R.string.notify_files, null, s.notifyFiles) { v -> app.prefs.update { it.copy(notifyFiles = v) } }
            SwitchRow(R.string.vibrate, null, s.vibrate) { v -> app.prefs.update { it.copy(vibrate = v) } }
            SwitchRow(R.string.sound, null, s.sound) { v -> app.prefs.update { it.copy(sound = v) } }

            Section(R.string.files)
            SwitchRow(R.string.auto_accept_voice, R.string.auto_accept_voice_hint, s.autoAcceptVoice) { v -> app.prefs.update { it.copy(autoAcceptVoice = v) } }
            SwitchRow(R.string.save_to_downloads, R.string.save_to_downloads_hint, s.saveToDownloads) { v -> app.prefs.update { it.copy(saveToDownloads = v) } }
            SwitchRow(R.string.accept_help, R.string.accept_help_hint, s.acceptHelp) { v -> app.prefs.update { it.copy(acceptHelp = v) } }

            Section(R.string.appearance)
            Column(Modifier.padding(horizontal = 8.dp)) {
                listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (m, label) ->
                    Row(Modifier.fillMaxWidth().clickable { app.prefs.update { it.copy(theme = m) } }.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.theme == m, onClick = { app.prefs.update { it.copy(theme = m) } })
                        Text(stringResource(label))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) SwitchRow(R.string.dynamic_color, R.string.dynamic_color_hint, s.dynamicColor) { v -> app.prefs.update { it.copy(dynamicColor = v) } }

            Section(R.string.background)
            SwitchRow(R.string.start_on_boot, null, s.startOnBoot) { v -> app.prefs.update { it.copy(startOnBoot = v) } }
            ClickRow(R.string.battery_optimization, stringResource(R.string.battery_optimization_hint)) {
                val pm = context.getSystemService(PowerManager::class.java)
                val i = if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName))
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { context.startActivity(i) }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) OutlinedButton(onClick = { app.stopService() }) { Text(stringResource(R.string.go_offline)) }
                else androidx.compose.material3.Button(onClick = { app.startService() }) { Text(stringResource(R.string.go_online)) }
            }

            Section(R.string.about)
            ClickRow(R.string.app_name, "v${BuildConfig.VERSION_NAME} · " + stringResource(R.string.compatible_with, "BeeBEEP 5.9.2 (protocol 96)"))
            ClickRow(R.string.license, stringResource(R.string.license_text))
            Spacer(Modifier.height(32.dp))
        }
    }

    when (dialog) {
        "nickname" -> TextDialog(R.string.nickname, s.nickname, onDismiss = { dialog = null }) { v -> if (v.isNotBlank()) app.prefs.update { it.copy(nickname = v.trim()) }; dialog = null }
        "statusDesc" -> TextDialog(R.string.status_description, s.statusDescription, onDismiss = { dialog = null }) { v -> app.prefs.update { it.copy(statusDescription = v.trim()) }; dialog = null }
        "password" -> TextDialog(R.string.network_password, s.networkPassword, onDismiss = { dialog = null }, hint = R.string.network_password_hint) { v -> app.prefs.update { it.copy(networkPassword = v) }; dialog = null }
        "listener" -> TextDialog(R.string.listener_port, s.listenerPort.toString(), onDismiss = { dialog = null }, number = true) { v -> v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> app.prefs.update { it.copy(listenerPort = p) } }; dialog = null }
        "file" -> TextDialog(R.string.file_port, s.filePort.toString(), onDismiss = { dialog = null }, number = true) { v -> v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> app.prefs.update { it.copy(filePort = p) } }; dialog = null }
        "manual" -> TextDialog(R.string.manual_addresses, s.manualAddresses.joinToString("\n"), onDismiss = { dialog = null }, hint = R.string.manual_addresses_hint, multiline = true) { v ->
            app.prefs.update { it.copy(manualAddresses = v.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }) }; dialog = null
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
private fun ClickRow(title: Int, value: String, hint: Int? = null, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: Int, hint: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun TextDialog(title: Int, initial: String, onDismiss: () -> Unit, hint: Int? = null, number: Boolean = false, multiline: Boolean = false, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (hint != null) { Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(v, { v = it }, singleLine = !multiline, minLines = if (multiline) 3 else 1, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---- vCard editor -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(app: BeeApp, onBack: () -> Unit) {
    val s by app.prefs.settings.collectAsState()
    val context = LocalContext.current
    var vc by remember { mutableStateOf(s.vcard) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) vc = vc.copy(photoPngBase64 = encodePhoto(scalePhoto(bmp)))
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.edit_profile)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = { app.prefs.update { it.copy(vcard = vc) }; onBack() }) { Icon(Icons.Default.Check, stringResource(R.string.save)) } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val photo = remember(vc.photoPngBase64) { decodePhoto(vc.photoPngBase64) }
                Box(Modifier.size(96.dp).clip(CircleShape).background(parseColor(s.color)).clickable { picker.launch("image/*") }, contentAlignment = Alignment.Center) {
                    if (photo != null) androidx.compose.foundation.Image(photo, null, Modifier.size(96.dp))
                    else Icon(Icons.Default.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                if (photo != null) TextButton(onClick = { vc = vc.copy(photoPngBase64 = "") }) { Text(stringResource(R.string.remove_photo)) }
            }
            Field(R.string.first_name, vc.firstName) { vc = vc.copy(firstName = it) }
            Field(R.string.last_name, vc.lastName) { vc = vc.copy(lastName = it) }
            Field(R.string.birthday, vc.birthday, hint = "yyyy-MM-dd") { vc = vc.copy(birthday = it) }
            Field(R.string.email, vc.email, KeyboardType.Email) { vc = vc.copy(email = it) }
            Field(R.string.phone, vc.phone, KeyboardType.Phone) { vc = vc.copy(phone = it) }
            Field(R.string.room, vc.roomLocation) { vc = vc.copy(roomLocation = it) }
            Field(R.string.info, vc.info, multiline = true) { vc = vc.copy(info = it) }
        }
    }
}

@Composable
private fun Field(label: Int, value: String, type: KeyboardType = KeyboardType.Text, hint: String? = null, multiline: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(stringResource(label)) }, placeholder = hint?.let { { Text(it) } }, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth()
    )
}

// ---- connection log ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(engine: BeeEngine, onBack: () -> Unit) {
    val lines by engine.logLines.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.connection_log)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (lines.isEmpty()) Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
