package com.ev.iletisim.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Aile sırrından türetilen anahtarlar ve AES-GCM çerçeve şifrelemesi. */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) {
            mac.update(ByteBuffer.allocate(4).putInt(p.size).array())
            mac.update(p)
        }
        return mac.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869), en fazla 32 bayt çıktı. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        require(length <= 32)
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            doFinal(secret)
        }
        val okm = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info.toByteArray())
            update(1.toByte())
            doFinal()
        }
        return okm.copyOf(length)
    }

    fun equalsConstantTime(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** Aynı aileye ait cihazları ayırt etmek için sırrı açığa vermeyen kısa kimlik. */
    fun familyId(secret: ByteArray): String =
        b64(MessageDigest.getInstance("SHA-256").digest(secret + "ev-family".toByteArray()).copyOf(8))

    fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

/** Tek yönlü şifreli kanal: her çerçevenin sıra numarası hem IV hem tekrar saldırısı korumasıdır. */
class FrameCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private var sendSeq = 0L
    private var recvSeq = -1L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val seq = sendSeq++
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv(seq)))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(8 + ct.size).putLong(seq).put(ct).array()
    }

    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(frame)
        val seq = buf.long
        if (seq <= recvSeq) throw SecurityException("Tekrarlanan çerçeve")
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv(seq)))
        val plain = c.doFinal(ct)
        recvSeq = seq
        return plain
    }

    private fun iv(seq: Long) = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
}
