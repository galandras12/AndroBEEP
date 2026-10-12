package com.galandras12.droidbeep.core

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Elliptic curve Diffie-Hellman on the Koblitz curve NIST K-163 (sect163k1), compatible with the
 * tiny ECDH implementation used by BeeBEEP (src/ecdh/ECDH.cpp, ECDH_CURVE_NIST_K163).
 *
 * Wire format used by BeeBEEP:
 *  - a private key is 24 random bytes (6 little endian 32 bit words), with every bit >= 162 cleared;
 *  - a public key is x (24 bytes, LE words) followed by y (24 bytes, LE words), written as 48 decimal
 *    numbers joined by ':';
 *  - the shared secret is the point scalar*other_pub, its 48 bytes written as decimal numbers with no
 *    separator, then base64url without padding.
 */
object Ecdh163 {
    const val PRIVATE_KEY_SIZE = 24
    const val PUBLIC_KEY_SIZE = 48
    private const val DEGREE = 163
    private const val WORD_BYTES = 24

    // Reduction polynomial x^163 + x^7 + x^6 + x^3 + 1
    private val POLY: BigInteger = BigInteger.ONE.shiftLeft(163)
        .or(BigInteger.valueOf(0xC9))

    private val BASE_X = bigFromWords(0x5c94eee8L, 0xde4e6d5eL, 0xaa07d793L, 0x7bbc11acL, 0xfe13c053L, 0x00000002L)
    private val BASE_Y = bigFromWords(0xccdaa3d9L, 0x0536d538L, 0x321f2e80L, 0x5d38ff58L, 0x89070fb0L, 0x00000002L)
    private val ORDER = bigFromWords(0x99f8a5efL, 0xa2e0cc0dL, 0x00020108L, 0x00000000L, 0x00000000L, 0x00000004L)

    private val random = SecureRandom()

    private fun bigFromWords(vararg words: Long): BigInteger {
        var r = BigInteger.ZERO
        for (i in words.indices.reversed()) {
            r = r.shiftLeft(32).or(BigInteger.valueOf(words[i] and 0xFFFFFFFFL))
        }
        return r
    }

    // ---- GF(2^163) arithmetic -------------------------------------------------------------

    private fun mul(a: BigInteger, b: BigInteger): BigInteger {
        var z = BigInteger.ZERO
        var t = a
        val bl = b.bitLength()
        for (i in 0 until bl) {
            if (b.testBit(i)) z = z.xor(t)
            t = t.shiftLeft(1)
            if (t.testBit(DEGREE)) t = t.xor(POLY)
        }
        return z
    }

    /** Inverse using the extended Euclidean algorithm on polynomials over GF(2). */
    private fun inv(a: BigInteger): BigInteger {
        var u = a
        var v = POLY
        var g1 = BigInteger.ONE
        var g2 = BigInteger.ZERO
        while (u != BigInteger.ONE) {
            var j = u.bitLength() - v.bitLength()
            if (j < 0) {
                val tu = u; u = v; v = tu
                val tg = g1; g1 = g2; g2 = tg
                j = -j
            }
            u = u.xor(v.shiftLeft(j))
            g1 = g1.xor(g2.shiftLeft(j))
        }
        return g1
    }

    // ---- curve arithmetic (a = 1, b = 1) --------------------------------------------------

    private class Point(val x: BigInteger, val y: BigInteger) {
        val isZero get() = x.signum() == 0 && y.signum() == 0
    }

    private val ZERO_POINT = Point(BigInteger.ZERO, BigInteger.ZERO)

    private fun double(p: Point): Point {
        if (p.x.signum() == 0) return ZERO_POINT
        // l = x + y/x ; x3 = l^2 + l + 1 ; y3 = x^2 + (l + 1) * x3
        val l = p.x.xor(mul(inv(p.x), p.y))
        val x3 = mul(l, l).xor(l).xor(BigInteger.ONE)
        val y3 = mul(p.x, p.x).xor(mul(l.xor(BigInteger.ONE), x3))
        return Point(x3, y3)
    }

    private fun add(p: Point, q: Point): Point {
        if (q.isZero) return p
        if (p.isZero) return q
        if (p.x == q.x) {
            return if (p.y == q.y) double(p) else ZERO_POINT
        }
        val a = p.y.xor(q.y)
        val b = p.x.xor(q.x)
        val c = mul(inv(b), a)
        val d = mul(c, c).xor(c).xor(b).xor(BigInteger.ONE)
        val x3 = p.x.xor(d)
        val y3 = p.y.xor(mul(x3, c).xor(d))
        return Point(d, y3)
    }

    private fun scalarMul(p: Point, k: BigInteger): Point {
        var r = ZERO_POINT
        for (i in k.bitLength() - 1 downTo 0) {
            r = double(r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    private fun onCurve(p: Point): Boolean {
        if (p.isZero) return true
        // y^2 + x*y = x^3 + x^2 + b   (a = 1, b = 1)
        val x2 = mul(p.x, p.x)
        val left = mul(p.y, p.y).xor(mul(p.x, p.y))
        val right = mul(x2, p.x).xor(x2).xor(BigInteger.ONE)
        return left == right
    }

    // ---- (de)serialization -----------------------------------------------------------------

    private fun toLittleEndian(v: BigInteger): ByteArray {
        val out = ByteArray(WORD_BYTES)
        for (i in 0 until WORD_BYTES) {
            var b = 0
            for (bit in 0 until 8) if (v.testBit(i * 8 + bit)) b = b or (1 shl bit)
            out[i] = b.toByte()
        }
        return out
    }

    private fun fromLittleEndian(bytes: ByteArray, offset: Int): BigInteger {
        var r = BigInteger.ZERO
        for (i in WORD_BYTES - 1 downTo 0) {
            r = r.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
        }
        return r
    }

    /** Clears every bit >= degree(order) - 1 = 162, as BeeBEEP does before using a private key. */
    fun clampPrivateKey(priv: ByteArray): ByteArray {
        val out = priv.copyOf()
        val nbits = ORDER.bitLength() // 163
        for (i in (nbits - 1) until WORD_BYTES * 8) {
            out[i / 8] = (out[i / 8].toInt() and (1 shl (i % 8)).inv()).toByte()
        }
        return out
    }

    fun generatePrivateKey(): ByteArray {
        val priv = ByteArray(PRIVATE_KEY_SIZE)
        random.nextBytes(priv)
        return clampPrivateKey(priv)
    }

    fun publicKeyBytes(privateKey: ByteArray): ByteArray {
        val d = fromLittleEndian(clampPrivateKey(privateKey), 0)
        val p = scalarMul(Point(BASE_X, BASE_Y), d)
        return toLittleEndian(p.x) + toLittleEndian(p.y)
    }

    fun publicKeyToString(pub: ByteArray): String = pub.joinToString(":") { (it.toInt() and 0xFF).toString() }

    fun publicKeyFromString(s: String): ByteArray? {
        val parts = s.split(":")
        if (parts.size != PUBLIC_KEY_SIZE) return null
        val out = ByteArray(PUBLIC_KEY_SIZE)
        for (i in parts.indices) {
            val v = parts[i].toLongOrNull() ?: return null
            out[i] = v.toByte() // same truncation as static_cast<quint8>
        }
        return out
    }

    /** Returns the 48 raw bytes of the shared point or null if the other public key is invalid. */
    fun sharedPoint(privateKey: ByteArray, otherPublic: ByteArray): ByteArray? {
        if (otherPublic.size != PUBLIC_KEY_SIZE) return null
        val other = Point(fromLittleEndian(otherPublic, 0), fromLittleEndian(otherPublic, WORD_BYTES))
        if (other.isZero || !onCurve(other)) return null
        val d = fromLittleEndian(clampPrivateKey(privateKey), 0)
        val s = scalarMul(other, d)
        return toLittleEndian(s.x) + toLittleEndian(s.y)
    }

    /** The shared key text BeeBEEP derives its cipher key from. */
    fun sharedKeyText(sharedPoint: ByteArray): ByteArray {
        val sb = StringBuilder()
        for (b in sharedPoint) sb.append(b.toInt() and 0xFF)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().toByteArray(Charsets.ISO_8859_1))
            .toByteArray(Charsets.ISO_8859_1)
    }
}

/** An ECDH key pair created per connection, like ECDH::Keys. */
class EcdhKeys {
    private var privateKey: ByteArray = Ecdh163.generatePrivateKey()
    val publicKey: String = Ecdh163.publicKeyToString(Ecdh163.publicKeyBytes(privateKey))

    /** Returns the shared key text (base64url of the decimal digits) or null on invalid key. */
    fun generateSharedKey(otherPublic: String): ByteArray? {
        val other = Ecdh163.publicKeyFromString(otherPublic) ?: return null
        val point = Ecdh163.sharedPoint(privateKey, other) ?: return null
        return Ecdh163.sharedKeyText(point)
    }

    internal fun setPrivateKeyForTest(priv: ByteArray) {
        privateKey = priv
    }
}
