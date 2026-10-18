package com.ev.iletisim.core

import org.json.JSONObject

/**
 * Yetişkin telefonun gösterdiği QR'ın içeriği: aile sırrı ve yeni cihaza verilen ad ve rol.
 * QR'ı gören herkes aileye katılabilir, bu yüzden ekranda sadece eşleştirme sırasında durur.
 */
data class PairingInvite(val familySecret: ByteArray, val name: String, val role: Role) {
    fun encode(): String = JSONObject()
        .put("app", "ev-iletisim")
        .put("v", 1)
        .put("s", Crypto.b64(familySecret))
        .put("n", name)
        .put("r", role.wire)
        .toString()

    companion object {
        /** QR içeriği bu uygulamaya ait değilse null döner. */
        fun decode(text: String): PairingInvite? = try {
            val o = JSONObject(text)
            if (o.optString("app") != "ev-iletisim" || o.optInt("v") != 1) null
            else {
                val secret = Crypto.unb64(o.getString("s"))
                val name = o.getString("n").trim()
                if (secret.size != 32 || name.isEmpty()) null
                else PairingInvite(secret, name, Role.fromWire(o.getString("r")))
            }
        } catch (e: Exception) {
            null
        }
    }
}
