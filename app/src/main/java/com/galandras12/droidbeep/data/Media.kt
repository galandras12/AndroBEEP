package com.galandras12.droidbeep.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.galandras12.droidbeep.core.LocalFile
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Helpers to read picked files and to store received ones. */
object Media {
    fun describe(context: Context, uri: Uri): LocalFile? {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        if (size < 0) {
            size = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1 } catch (_: Exception) { -1 }
        }
        if (size < 0) return null
        val mime = cr.getType(uri) ?: guessMime(name!!)
        return LocalFile(uri.toString(), name!!, size, System.currentTimeMillis(), mime)
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Copies a received file into the public Downloads/BeeBeep folder. Returns the content uri or null. */
    fun exportToDownloads(context: Context, file: File, mime: String?): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime?.takeIf { it.isNotEmpty() } ?: guessMime(file.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BeeBeep")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BeeBeep").also { it.mkdirs() }
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Records the microphone as 16 bit mono PCM into a WAV file (what the BeeBEEP desktop client plays). */
class VoiceRecorder(val outFile: File) {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var startedAt = 0L
    var durationMs: Long = 0
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (_: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        outFile.parentFile?.mkdirs()
        recording = true
        startedAt = System.currentTimeMillis()
        thread = Thread({
            try {
                FileOutputStream(outFile).use { fos ->
                    fos.write(ByteArray(44)) // WAV header placeholder
                    val buf = ByteArray(min)
                    var total = 0L
                    rec.startRecording()
                    while (recording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) { fos.write(buf, 0, n); total += n }
                    }
                    rec.stop()
                    fos.flush()
                    writeHeader(total, rate)
                }
            } catch (_: Exception) {
            } finally {
                rec.release()
            }
        }, "voice-recorder").also { it.start() }
        return true
    }

    private fun writeHeader(dataLen: Long, rate: Int) {
        RandomAccessFile(outFile, "rw").use { f ->
            fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.seek(0)
            f.write("RIFF".toByteArray()); f.write(le32(36 + dataLen)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
            f.write(le32(rate.toLong())); f.write(le32(rate * 2L)); f.write(le16(2)); f.write(le16(16))
            f.write("data".toByteArray()); f.write(le32(dataLen))
        }
    }

    /** Stops and returns true if something was recorded. */
    fun stop(): Boolean {
        if (!recording) return false
        recording = false
        durationMs = System.currentTimeMillis() - startedAt
        thread?.join(1500)
        return outFile.exists() && outFile.length() > 44 + 3200
    }

    fun cancel() {
        recording = false
        thread?.join(1500)
        outFile.delete()
    }
}
