package com.galandras12.droidbeep.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

fun parseColor(hex: String, fallback: Color = Color(0xFF757575)): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (_: Exception) {
    fallback
}

fun colorToHex(c: Color): String = "#%06X".format(0xFFFFFF and c.value.shr(32).toInt())

fun initialOf(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

fun formatSpeed(bytesPerSecond: Long): String = formatSize(bytesPerSecond) + "/s"

fun formatClock(t: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(t)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Time for lists: clock for today, short date otherwise. */
fun formatListTime(t: Instant): String {
    if (t == Instant.EPOCH) return ""
    val z = t.atZone(ZoneId.systemDefault())
    return if (z.toLocalDate() == LocalDate.now()) formatClock(t)
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date.from(t))
}

fun formatDay(t: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withZone(ZoneId.systemDefault()).format(t)

fun dayOf(t: Instant): LocalDate = t.atZone(ZoneId.systemDefault()).toLocalDate()

fun decodePhoto(base64: String): ImageBitmap? {
    if (base64.isBlank()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

fun encodePhoto(bitmap: Bitmap): String {
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

fun scalePhoto(src: Bitmap, size: Int = 96): Bitmap {
    val side = minOf(src.width, src.height)
    val x = (src.width - side) / 2
    val y = (src.height - side) / 2
    val square = Bitmap.createBitmap(src, x, y, side, side)
    return Bitmap.createScaledBitmap(square, size, size, true)
}

private val URL_REGEX = Regex("(https?://[^\\s<>]+|www\\.[^\\s<>]+)", RegexOption.IGNORE_CASE)

data class TextPart(val text: String, val url: String? = null)

fun splitLinks(text: String): List<TextPart> {
    val out = ArrayList<TextPart>()
    var last = 0
    for (m in URL_REGEX.findAll(text)) {
        if (m.range.first > last) out.add(TextPart(text.substring(last, m.range.first)))
        val raw = m.value.trimEnd('.', ',', ')', ';', '!', '?')
        out.add(TextPart(raw, if (raw.startsWith("www.", true)) "http://$raw" else raw))
        last = m.range.first + raw.length
    }
    if (last < text.length) out.add(TextPart(text.substring(last)))
    return out
}
