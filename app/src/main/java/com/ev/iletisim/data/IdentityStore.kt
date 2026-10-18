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

    fun join(invite: PairingInvite): Identity = save(invite.familySecret, invite.name, invite.role)

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
