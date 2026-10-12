package com.galandras12.droidbeep.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Engine calls do blocking socket I/O: they run on one background thread, in order. */
object Io {
    val dispatcher = Dispatchers.IO.limitedParallelism(1)
}

fun CoroutineScope.io(block: () -> Unit) {
    launch(Io.dispatcher) { runCatching { block() } }
}

fun openFile(context: Context, path: String, mime: String) {
    runCatching {
        val file = File(path)
        val uri: Uri = if (path.startsWith("content://")) Uri.parse(path) else FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifEmpty { com.galandras12.droidbeep.data.Media.guessMime(file.name) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
