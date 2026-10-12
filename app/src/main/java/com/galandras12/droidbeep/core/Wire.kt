package com.galandras12.droidbeep.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Block framing of ConnectionSocket::serializeData / readBlock.
 *
 * protocol > 60:  quint32 (4 + n) | quint32 n | n bytes        (big endian)
 * protocol <= 60: quint16 (4 + n) | quint32 n | n bytes        (used for the HELLO exchange)
 */
object Wire {
    private const val MAX_BLOCK = 256 * 1024 * 1024

    fun frame(payload: ByteArray, protoVersion: Int): ByteArray {
        val n = payload.size
        return if (protoVersion > Proto.SECURE_LEVEL_2) {
            val out = ByteArray(8 + n)
            putInt(out, 0, n + 4)
            putInt(out, 4, n)
            System.arraycopy(payload, 0, out, 8, n)
            out
        } else {
            require(n <= 65519) { "HELLO block too big for 16 bit framing: $n" }
            val out = ByteArray(6 + n)
            out[0] = ((n + 4) ushr 8).toByte()
            out[1] = (n + 4).toByte()
            putInt(out, 2, n)
            System.arraycopy(payload, 0, out, 6, n)
            out
        }
    }

    fun write(out: OutputStream, payload: ByteArray, protoVersion: Int) {
        out.write(frame(payload, protoVersion))
        out.flush()
    }

    /** Reads one block; throws EOFException when the stream is closed. */
    fun read(input: DataInputStream, protoVersion: Int): ByteArray {
        val blockSize: Long = if (protoVersion > Proto.SECURE_LEVEL_2) {
            input.readInt().toLong() and 0xFFFFFFFFL
        } else {
            input.readUnsignedShort().toLong()
        }
        val n = input.readInt().toLong() and 0xFFFFFFFFL
        if (n == 0xFFFFFFFFL) return ByteArray(0) // null QByteArray
        if (n > MAX_BLOCK || blockSize < 4 || n != blockSize - 4) {
            throw java.io.IOException("Invalid block size: block=$blockSize array=$n")
        }
        val buf = ByteArray(n.toInt())
        input.readFully(buf)
        return buf
    }

    private fun putInt(a: ByteArray, off: Int, v: Int) {
        a[off] = (v ushr 24).toByte()
        a[off + 1] = (v ushr 16).toByte()
        a[off + 2] = (v ushr 8).toByte()
        a[off + 3] = v.toByte()
    }
}

class ClosedException(msg: String) : Exception(msg)
