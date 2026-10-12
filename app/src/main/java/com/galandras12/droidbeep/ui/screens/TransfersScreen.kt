package com.galandras12.droidbeep.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galandras12.droidbeep.R
import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.SharedFile
import com.galandras12.droidbeep.core.TransferInfo
import com.galandras12.droidbeep.core.TransferState
import com.galandras12.droidbeep.data.Media
import com.galandras12.droidbeep.ui.components.formatSize
import com.galandras12.droidbeep.ui.components.formatSpeed
import com.galandras12.droidbeep.ui.components.io
import com.galandras12.droidbeep.ui.components.openFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(engine: BeeEngine) {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.files)) }, actions = {
            if (tab == 0) IconButton(onClick = { engine.clearFinishedTransfers() }) { Icon(Icons.Default.Delete, stringResource(R.string.clear_finished)) }
            if (tab == 2) IconButton(onClick = { scope.io { engine.requestSharedFiles() } }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.transfers)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.my_shares)) })
                Tab(selected = tab == 2, onClick = { tab = 2; scope.io { engine.requestSharedFiles() } }, text = { Text(stringResource(R.string.network_shares)) })
            }
            when (tab) {
                0 -> TransferList(engine)
                1 -> MyShares(engine)
                else -> NetworkShares(engine)
            }
        }
    }
}

@Composable
private fun TransferList(engine: BeeEngine) {
    val list by engine.transferList.collectAsState()
    val peers by engine.peers.collectAsState()
    val context = LocalContext.current
    if (list.isEmpty()) {
        EmptyHint(stringResource(R.string.no_transfers))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.key }) { t ->
            val peerName = peers.firstOrNull { it.key == t.peerKey }?.displayName ?: ""
            TransferRow(t, peerName, onCancel = { engine.cancelTransfer(t.key) }, onOpen = { t.localPath?.let { p -> openFile(context, p, "") } })
        }
    }
}

@Composable
private fun TransferRow(t: TransferInfo, peerName: String, onCancel: () -> Unit, onOpen: () -> Unit) {
    val active = t.state == TransferState.TRANSFERRING || t.state == TransferState.CONNECTING || t.state == TransferState.QUEUED
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                t.state == TransferState.COMPLETED -> Icons.Default.CheckCircle
                t.state == TransferState.ERROR || t.state == TransferState.CANCELED || t.state == TransferState.REFUSED -> Icons.Default.Error
                t.upload -> Icons.Default.CloudUpload
                else -> Icons.Default.CloudDownload
            }, null,
            tint = when (t.state) {
                TransferState.COMPLETED -> MaterialTheme.colorScheme.tertiary
                TransferState.ERROR, TransferState.CANCELED, TransferState.REFUSED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    stringResource(if (t.upload) R.string.upload_to else R.string.download_from, peerName),
                    formatSize(t.size),
                    if (active && t.bytesPerSecond > 0) formatSpeed(t.bytesPerSecond) else null,
                    if (t.state == TransferState.ERROR && t.error.isNotEmpty()) t.error else null,
                ).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (active) {
                if (t.size > 0 && t.transferred > 0) LinearProgressIndicator(progress = { (t.transferred.toFloat() / t.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (active) IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, stringResource(R.string.cancel)) }
        else if (t.state == TransferState.COMPLETED && !t.upload && t.localPath != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.open)) }
    }
}

@Composable
private fun MyShares(engine: BeeEngine) {
    val shares by engine.localShares.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.mapNotNull { u ->
            runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Media.describe(context, u)
        }
        if (files.isNotEmpty()) scope.io { engine.shareFiles(files) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "folder"
            scope.io { engine.shareFiles(com.galandras12.droidbeep.data.AndroidHostAccess.listFolder(context, uri.toString(), name)) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.AddCircle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share_files))
            }
            androidx.compose.material3.OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Default.FolderShared, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share_folder))
            }
        }
        if (shares.isEmpty()) EmptyHint(stringResource(R.string.no_shares_hint))
        else LazyColumn(Modifier.fillMaxSize()) {
            items(shares, key = { it.uri }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (f.shareFolder.isNotEmpty()) f.shareFolder + " · " else "") + formatSize(f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.io { engine.unshareFile(f.uri) } }) { Icon(Icons.Default.Delete, stringResource(R.string.stop_sharing)) }
                }
            }
        }
    }
}

@Composable
private fun NetworkShares(engine: BeeEngine) {
    val shares by engine.remoteShares.collectAsState()
    val peers by engine.peers.collectAsState()
    val scope = rememberCoroutineScope()
    if (shares.isEmpty()) {
        EmptyHint(stringResource(R.string.no_network_shares))
        return
    }
    val byOwner = shares.groupBy { it.ownerKey }
    LazyColumn(Modifier.fillMaxSize()) {
        byOwner.forEach { (owner, files) ->
            item(key = "h$owner") {
                Text(peers.firstOrNull { it.key == owner }?.displayName ?: owner, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp))
            }
            items(files, key = { "${it.ownerKey}/${it.info.id}" }) { sf: SharedFile ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sf.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (sf.info.shareFolder.isNotEmpty()) sf.info.shareFolder + " · " else "") + formatSize(sf.info.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.io { engine.downloadShared(sf) } }) { Icon(Icons.Default.Download, stringResource(R.string.download)) }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
