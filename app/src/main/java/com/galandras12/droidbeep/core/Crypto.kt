package com.galandras12.droidbeep.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cipher helpers that reproduce BeeBEEP's Protocol::encryptByteArray / decryptByteArray / createCipherKey:
 * AES-256 in ECB mode over 16 byte blocks (Rijndael), where the key bytes come from a hex string.
 */
object Crypto {
    const val BLOCK_SIZE = 16
    private const val KEY_SIZE = 32

    /** Port of Protocol::hexToUnsignedChar including its handling of non hex characters / short input. */
    fun hexToKeyBytes(hex: ByteArray, outLen: Int = KEY_SIZE): ByteArray {
        val out = ByteArray(outLen)
        var i = 0
        var j = 0
        var msb = true
        fun at(idx: Int): Int = if (idx < hex.size) hex[idx].toInt() and 0xFF else 0
        // C++ reads a NUL terminated string; the effective length stops at the first NUL.
        var strlen = 0
        while (strlen < hex.size && hex[strlen].toInt() != 0) strlen++
        while (j < outLen) {
            val c = if (i < strlen) at(i) else 0
            if (c in '0'.code..'9'.code) {
                if (msb) {
                    out[j] = ((c - '0'.code) * 16).toByte(); i++; msb = false
                } else {
                    out[j] = (out[j].toInt() or (c - '0'.code)).toByte(); j++; i++; msb = true
                }
            } else if (c.toChar().uppercaseChar() in 'A'..'F') {
                val v = c.toChar().uppercaseChar().code - 'A'.code + 10
                if (msb) {
                    out[j] = (v * 16).toByte(); i++; msb = false
                } else {
                    out[j] = (out[j].toInt() or v).toByte(); j++; i++; msb = true
                }
            } else {
                if (i < strlen) i++
                if (msb) {
                    out[j] = 0; j++
                } else {
                    msb = true
                }
            }
        }
        return out
    }

    /** Key for the legacy "level 1/2" encryption (protocol < 73): the "What the hell" key. */
    private fun legacyKey(cipherKey: ByteArray): ByteArray {
        // The original reads `cipher_key.size() < i ? cipher_key.at(i) : 0`, which is the
        // "wrong way round": for every real key (a 40 chars hex string) the result is an all zero key.
        val key = ByteArray(KEY_SIZE)
        for (i in 0 until KEY_SIZE) {
            key[i] = if (cipherKey.size < i && i < cipherKey.size) cipherKey[i] else 0
        }
        return key
    }

    private fun keyFor(cipherKey: ByteArray, protoVersion: Int): ByteArray =
        if (protoVersion < Proto.SECURE_LEVEL_3) legacyKey(cipherKey) else hexToKeyBytes(cipherKey)

    /** Encrypts [data]; a trailing partial block is appended in clear, like the original. */
    fun encrypt(data: ByteArray, cipherKey: ByteArray, protoVersion: Int): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        if (cipherKey.isEmpty()) return java.util.Base64.getEncoder().encode(data)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyFor(cipherKey, protoVersion), "AES"))
        return transform(cipher, data)
    }

    fun decrypt(data: ByteArray, cipherKey: ByteArray, protoVersion: Int): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        if (cipherKey.isEmpty()) return java.util.Base64.getMimeDecoder().decode(data)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFor(cipherKey, protoVersion), "AES"))
        return transform(cipher, data)
    }

    private fun transform(cipher: Cipher, data: ByteArray): ByteArray {
        val full = data.size / BLOCK_SIZE * BLOCK_SIZE
        val out = ByteArray(data.size)
        if (full > 0) cipher.doFinal(data, 0, full, out, 0)
        if (full < data.size) System.arraycopy(data, full, out, full, data.size - full)
        return out
    }

    /** Protocol::createCipherKey(shared_key, datastream_version): hex of SHA1 (ds < 13) or SHA3-256. */
    fun createCipherKey(sharedKey: ByteArray, datastreamVersion: Int): ByteArray {
        if (sharedKey.isEmpty()) return sharedKey
        val digest = if (datastreamVersion < 13) {
            MessageDigest.getInstance("SHA-1").digest(sharedKey)
        } else {
            Sha3.sha3_256(sharedKey)
        }
        return digest.toHex().toByteArray(Charsets.ISO_8859_1)
    }

    /** Settings::hash(): hex(SHA1(utf8(text) + passwordHash)). */
    fun hash(text: String, passwordHash: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(text.toByteArray(Charsets.UTF_8))
        md.update(passwordHash)
        return md.digest().toHex()
    }

    /** Settings::setPassword(): hex(SHA1("*6475*")) for the default / empty password. */
    fun passwordHash(password: String): ByteArray {
        val pwd = if (password.isEmpty() || password == "*") "*6475*" else password
        return MessageDigest.getInstance("SHA-1").digest(pwd.toByteArray(Charsets.UTF_8)).toHex()
            .toByteArray(Charsets.ISO_8859_1)
    }

    /** Settings::simpleHash(): hex(SHA256). */
    fun simpleHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHex()
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

/** Minimal SHA3-256 (Keccak) so that peers announcing a Qt datastream version >= 13 interoperate on every Android version. */
@OptIn(ExperimentalUnsignedTypes::class)
object Sha3 {
    private val RC: LongArray = ulongArrayOf(
        0x0000000000000001UL, 0x0000000000008082UL, 0x800000000000808aUL, 0x8000000080008000UL,
        0x000000000000808bUL, 0x0000000080000001UL, 0x8000000080008081UL, 0x8000000000008009UL,
        0x000000000000008aUL, 0x0000000000000088UL, 0x0000000080008009UL, 0x000000008000000aUL,
        0x000000008000808bUL, 0x800000000000008bUL, 0x8000000000008089UL, 0x8000000000008003UL,
        0x8000000000008002UL, 0x8000000000000080UL, 0x000000000000800aUL, 0x800000008000000aUL,
        0x8000000080008081UL, 0x8000000000008080UL, 0x0000000080000001UL, 0x8000000080008008UL
    ).map { it.toLong() }.toLongArray()
    private val ROT = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    )
    private val PI = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    )

    private fun keccakF(st: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                var j = 0
                while (j < 25) { st[j + i] = st[j + i] xor t; j += 5 }
            }
            var t = st[1]
            for (i in 0 until 24) {
                val j = PI[i]
                val b = st[j]
                st[j] = java.lang.Long.rotateLeft(t, ROT[i])
                t = b
            }
            var j = 0
            while (j < 25) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = st[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                j += 5
            }
            st[0] = st[0] xor RC[round]
        }
    }

    fun sha3_256(input: ByteArray): ByteArray {
        val rate = 136
        val st = LongArray(25)
        val padded = ByteArray(((input.size / rate) + 1) * rate)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = (padded[input.size].toInt() xor 0x06).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        var off = 0
        while (off < padded.size) {
            for (i in 0 until rate / 8) {
                var v = 0L
                for (b in 0 until 8) v = v or ((padded[off + i * 8 + b].toLong() and 0xFF) shl (8 * b))
                st[i] = st[i] xor v
            }
            keccakF(st)
            off += rate
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (st[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }
}
