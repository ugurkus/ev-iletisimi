package com.ev.iletisim.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Bu cihazın kimliği. Sır, eşleştirme QR'ından gelir ve tüm ailede aynıdır. */
data class Identity(val deviceId: String, val name: String, val role: Role, val familySecret: ByteArray)

enum class Role(val wire: String, val label: String) {
    ADMIN("admin", "Yetişkin"), CHILD("child", "Çocuk");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s } ?: CHILD
    }
}

data class PeerInfo(val deviceId: String, val name: String, val role: Role)

class HandshakeException(msg: String) : IOException(msg)

/**
 * Kimliği doğrulanmış, şifreli bağlantı.
 *
 * El sıkışma: iki taraf da açık "hello" yollar, sonra aile sırrıyla iki hello'nun HMAC'ini
 * kanıt olarak gönderir. Sırrı bilmeyen cihaz kanıt üretemez. Oturum anahtarları iki tarafın
 * rastgele nonce'larından türetildiği için her bağlantıda farklıdır.
 */
class SecureChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val peer: PeerInfo,
    private val sendCipher: FrameCipher,
    private val recvCipher: FrameCipher,
) {
    fun send(obj: JSONObject) {
        val plain = obj.toString().toByteArray(Charsets.UTF_8)
        // Şifreleme ve yazma aynı kilitte olmalı, yoksa sıra numaraları karışır ve alıcı reddeder.
        synchronized(output) { writeFrame(output, sendCipher.seal(plain)) }
    }

    /** Bağlantı kapanınca IOException fırlatır. */
    fun receive(): JSONObject = JSONObject(String(recvCipher.open(readFrame(input)), Charsets.UTF_8))

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME = 1 shl 20

        fun handshake(me: Identity, inStream: InputStream, outStream: OutputStream): SecureChannel {
            val input = DataInputStream(inStream)
            val output = DataOutputStream(outStream)
            val secret = me.familySecret
            val myNonce = Crypto.randomBytes(16)
            val myHello = JSONObject()
                .put("v", VERSION)
                .put("fam", Crypto.familyId(secret))
                .put("id", me.deviceId)
                .put("name", me.name)
                .put("role", me.role.wire)
                .put("nonce", Crypto.b64(myNonce))
                .toString().toByteArray(Charsets.UTF_8)
            writeFrame(output, myHello)
            val theirHello = readFrame(input)
            val hello = JSONObject(String(theirHello, Charsets.UTF_8))
            if (hello.optInt("v") != VERSION) throw HandshakeException("Sürüm uyuşmuyor")
            if (hello.optString("fam") != Crypto.familyId(secret)) throw HandshakeException("Başka aile")
            val peerId = hello.getString("id")
            if (peerId == me.deviceId) throw HandshakeException("Kendine bağlantı")
            val theirNonce = Crypto.unb64(hello.getString("nonce"))

            writeFrame(output, Crypto.hmac(secret, "ev-auth".toByteArray(), myHello, theirHello))
            val proof = readFrame(input)
            val expected = Crypto.hmac(secret, "ev-auth".toByteArray(), theirHello, myHello)
            if (!Crypto.equalsConstantTime(proof, expected)) throw HandshakeException("Aile sırrı yanlış")

            val salt = if (Crypto.b64(myNonce) < Crypto.b64(theirNonce)) myNonce + theirNonce else theirNonce + myNonce
            val sendKey = Crypto.hkdf(secret, salt, "ev-session|${me.deviceId}")
            val recvKey = Crypto.hkdf(secret, salt, "ev-session|$peerId")
            val peer = PeerInfo(peerId, hello.optString("name", "?"), Role.fromWire(hello.optString("role")))
            return SecureChannel(input, output, peer, FrameCipher(sendKey), FrameCipher(recvKey))
        }

        private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        private fun readFrame(input: DataInputStream): ByteArray {
            val len = input.readInt()
            if (len < 0 || len > MAX_FRAME) throw IOException("Geçersiz çerçeve boyu: $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
