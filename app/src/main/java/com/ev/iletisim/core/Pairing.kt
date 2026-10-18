package com.ev.iletisim.core

import org.json.JSONObject

/**
 * Yetişkin telefonun gösterdiği QR'ın içeriği: aile sırrı ve yeni cihaza verilen ad ve rol.
 * QR'ı gören herkes aileye katılabilir, bu yüzden ekranda sadece eşleştirme sırasında durur.
 */
data class PairingInvite(
    val familySecret: ByteArray,
    val name: String,
    val role: Role,
    /** QR'ı gösteren cihazın adresi; keşif çalışmazsa yeni cihaz doğrudan buraya bağlanır. */
    val inviter: Hint? = null,
) {
    data class Hint(val deviceId: String, val host: String, val port: Int)

    fun encode(): String = JSONObject()
        .put("app", "ev-iletisim")
        .put("v", 1)
        .put("s", Crypto.b64(familySecret))
        .put("n", name)
        .put("r", role.wire)
        .apply {
            if (inviter != null) put("id", inviter.deviceId).put("h", inviter.host).put("p", inviter.port)
        }
        .toString()
        .let(::asciiOnly)

    companion object {
        /**
         * QR okuyucuların çoğu Türkçe harfleri (ğ, ş, ı, İ…) bozuyor; JSON'da \\uXXXX
         * kaçışıyla yazılınca QR'da yalnızca ASCII kalır, ad JSON okunurken geri gelir.
         */
        fun asciiOnly(json: String): String = buildString {
            for (ch in json) {
                if (ch.code < 0x80) append(ch) else append("\\u%04x".format(ch.code))
            }
        }

        /** QR içeriği bu uygulamaya ait değilse null döner. */
        fun decode(text: String): PairingInvite? = try {
            val o = JSONObject(text)
            if (o.optString("app") != "ev-iletisim" || o.optInt("v") != 1) null
            else {
                val secret = Crypto.unb64(o.getString("s"))
                val name = o.getString("n").trim()
                if (secret.size != 32 || name.isEmpty()) null
                else {
                    val hint = if (o.has("id") && o.has("h") && o.optInt("p") in 1..65535) {
                        Hint(o.getString("id"), o.getString("h"), o.getInt("p"))
                    } else null
                    PairingInvite(secret, name, Role.fromWire(o.getString("r")), hint)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
