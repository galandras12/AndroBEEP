package com.galandras12.droidbeep.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galandras12.droidbeep.core.Chat
import com.galandras12.droidbeep.core.ChatType
import com.galandras12.droidbeep.core.Peer
import com.galandras12.droidbeep.core.UserStatus

fun statusColor(status: Int, online: Boolean): Color = when {
    !online -> Color(0xFF9E9E9E)
    status == UserStatus.Busy -> Color(0xFFD32F2F)
    status == UserStatus.Away -> Color(0xFFF9A825)
    else -> Color(0xFF2E9E4F)
}

@Composable
fun StatusDot(status: Int, online: Boolean, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(statusColor(status, online))
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
    )
}

@Composable
fun PeerAvatar(peer: Peer?, size: Dp = 48.dp, showStatus: Boolean = true, name: String? = null, colorHex: String? = null) {
    val photo = remember(peer?.vcard?.photoPngBase64) { peer?.vcard?.photoPngBase64?.let { decodePhoto(it) } }
    val label = name ?: peer?.displayName ?: "?"
    val color = parseColor(colorHex ?: peer?.color ?: "#757575")
    Box(Modifier.size(size)) {
        if (photo != null) {
            Image(photo, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(initialOf(label), color = contrastOn(color), fontSize = (size.value * 0.42f).sp)
            }
        }
        if (showStatus && peer != null) {
            StatusDot(peer.status, peer.online, size = (size.value * 0.28f).dp, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

fun contrastOn(c: Color): Color = if (c.luminance() > 0.55f) Color(0xFF1B1B1B) else Color.White

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

@Composable
fun ChatAvatar(chat: Chat, peers: Map<String, Peer>, size: Dp = 48.dp) {
    when (chat.type) {
        ChatType.PRIVATE -> PeerAvatar(peers[chat.members.firstOrNull()], size, name = chat.name)
        ChatType.GROUP -> Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(size * 0.55f))
        }
        ChatType.DEFAULT -> Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Forum, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.55f))
        }
    }
}
