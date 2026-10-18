package com.ev.iletisim.data

import android.content.Context
import com.ev.iletisim.core.Crypto
import com.ev.iletisim.core.Identity
import com.ev.iletisim.core.PairingInvite
import com.ev.iletisim.core.Role
import java.util.UUID

/** Cihaz kimliği ve aile sırrı. Uygulamanın kendi özel alanında saklanır. */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    fun load(): Identity? {
        val id = prefs.getString("id", null) ?: return null
        val secret = prefs.getString("secret", null) ?: return null
        return Identity(
            deviceId = id,
            name = prefs.getString("name", "?")!!,
            role = Role.fromWire(prefs.getString("role", "child")!!),
            familySecret = Crypto.unb64(secret),
        )
    }

    /** İlk yetişkin telefon yeni bir aile kurar. */
    fun createFamily(name: String): Identity = save(Crypto.randomBytes(32), name, Role.ADMIN)

    fun join(invite: PairingInvite): Identity {
        val identity = save(invite.familySecret, invite.name, invite.role)
        invite.inviter?.let {
            prefs.edit().putString("hint_id", it.deviceId).putString("hint_host", it.host).putInt("hint_port", it.port).apply()
        }
        return identity
    }

    /** Cihazın adını değiştirir; diğer cihazlar bir sonraki bağlantıda yeni adı görür. */
    fun rename(name: String) {
        prefs.edit().putString("name", name).apply()
    }

    /** Katılırken QR'dan öğrenilen ilk cihazın adresi (yoksa null). */
    fun inviterHint(): PairingInvite.Hint? {
        val id = prefs.getString("hint_id", null) ?: return null
        val host = prefs.getString("hint_host", null) ?: return null
        return PairingInvite.Hint(id, host, prefs.getInt("hint_port", 0))
    }

    private fun save(secret: ByteArray, name: String, role: Role): Identity {
        val identity = Identity(UUID.randomUUID().toString(), name, role, secret)
        prefs.edit()
            .putString("id", identity.deviceId)
            .putString("name", name)
            .putString("role", role.wire)
            .putString("secret", Crypto.b64(secret))
            .apply()
        return identity
    }
}
