package com.ev.iletisim.core

import com.ev.iletisim.net.BroadcastDiscovery
import com.ev.iletisim.net.PeerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Cihazların birbirini bulması ve keşif tek yönlü çalışsa bile bağlanması. */
class DiscoveryTest {
    private val secret = Crypto.randomBytes(32)
    private val started = mutableListOf<Device>()

    @After fun tearDown() = started.forEach { it.manager.stop() }

    private fun device(name: String, id: String) =
        Device(name, Role.CHILD, secret, id).also { it.start(); started.add(it) }

    private fun waitUntil(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Zaman aşımı")
            Thread.sleep(50)
        }
    }

    @Test fun announcementRoundTrip() {
        val fam = Crypto.familyId(secret)
        val a = BroadcastDiscovery.parse(BroadcastDiscovery.encode(fam, "aaaa", 47100))!!
        assertEquals(fam, a.family)
        assertEquals("aaaa", a.deviceId)
        assertEquals(47100, a.port)
        assertNull(BroadcastDiscovery.parse("evb1|x|y|70000"))
        assertNull(BroadcastDiscovery.parse("başka bir şey"))
    }

    @Test fun inviteCarriesInviterAddress() {
        val hint = PairingInvite.Hint("aaaa", "192.168.1.20", 47100)
        val back = PairingInvite.decode(PairingInvite(secret, "Tablet", Role.CHILD, hint).encode())!!
        assertEquals(hint, back.inviter)
        // Eski QR'lar (adres yok) hâlâ okunur
        assertNull(PairingInvite.decode(PairingInvite(secret, "Tablet", Role.CHILD).encode())!!.inviter)
    }

    @Test fun turkishNameSurvivesQr() {
        val name = "Ayşe'nin tableti ğüşıöç İĞÜŞÖÇ"
        val text = PairingInvite(secret, name, Role.CHILD).encode()
        assertTrue("QR içeriği yalnızca ASCII olmalı", text.all { it.code < 0x80 })
        // QR okuyucu metni Latin-1 olarak bile okusa ASCII bozulmaz
        val viaLatin1 = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        assertEquals(name, PairingInvite.decode(viaLatin1)!!.name)
    }

    @Test fun onlyLargerIdSeesTheOtherStillConnects() {
        // Keşif yalnız bir yönde çalıştı: küçük kimlikli cihaz karşıyı hiç görmedi.
        val a = device("Baba", "aaaa")
        val b = device("Ayşe", "bbbb")
        b.manager.onEndpoint("aaaa", InetAddress.getLoopbackAddress(), a.manager.port)
        waitUntil(15_000) { a.online.contains("bbbb") && b.online.contains("aaaa") }
    }

    @Test fun busyPreferredPortFallsBack() {
        val blocker = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val d = Device("Baba", Role.ADMIN, secret, "aaaa")
        started.add(d)
        d.manager.start(InetAddress.getLoopbackAddress(), blocker.localPort)
        assertTrue(d.manager.port > 0 && d.manager.port != blocker.localPort)
        blocker.close()
    }
}
