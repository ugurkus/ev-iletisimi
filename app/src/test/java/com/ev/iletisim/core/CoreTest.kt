package com.ev.iletisim.core

import com.ev.iletisim.net.PeerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MemoryStore : MessageStore {
    val messages = ConcurrentHashMap<String, ChatMessage>()
    override fun saveOutgoing(m: ChatMessage) { messages[m.id] = m }
    override fun pendingFor(peerId: String) =
        messages.values.filter { it.to == peerId && it.status == MessageStatus.PENDING }.sortedBy { it.sentAt }
    override fun markDelivered(id: String) {
        messages.computeIfPresent(id) { _, m -> if (m.status == MessageStatus.PENDING) m.copy(status = MessageStatus.DELIVERED) else m }
    }
    override fun saveIncoming(m: ChatMessage) = messages.putIfAbsent(m.id, m) == null
}

class Device(name: String, role: Role, secret: ByteArray, id: String) : PeerManager.Events {
    val identity = Identity(id, name, role, secret)
    val store = MemoryStore()
    val inbox = CopyOnWriteArrayList<ChatMessage>()
    val online = ConcurrentHashMap.newKeySet<String>()
    val manager = PeerManager(identity, store, this)
    override fun onPeerOnline(peer: PeerInfo) { online.add(peer.deviceId) }
    override fun onPeerOffline(peerId: String) { online.remove(peerId) }
    override fun onIncoming(m: ChatMessage) { inbox.add(m) }
    override fun onDelivered(messageId: String) = Unit
    fun start() = manager.start(InetAddress.getLoopbackAddress())
}

class CoreTest {
    private val secret = Crypto.randomBytes(32)
    private val started = mutableListOf<Device>()

    @After fun tearDown() = started.forEach { it.manager.stop() }

    private fun device(name: String, id: String, s: ByteArray = secret) =
        Device(name, Role.CHILD, s, id).also { it.start(); started.add(it) }

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Zaman aşımı")
            Thread.sleep(20)
        }
    }

    @Test fun pairingInviteRoundTrip() {
        val invite = PairingInvite(secret, "Ayşe tablet", Role.CHILD)
        val back = PairingInvite.decode(invite.encode())!!
        assertTrue(back.familySecret.contentEquals(secret))
        assertEquals("Ayşe tablet", back.name)
        assertEquals(Role.CHILD, back.role)
        assertNull(PairingInvite.decode("https://example.com"))
    }

    @Test fun frameCipherRejectsReplayAndTampering() {
        val key = Crypto.randomBytes(32)
        val sender = FrameCipher(key)
        val receiver = FrameCipher(key)
        val f1 = sender.seal("merhaba".toByteArray())
        assertEquals("merhaba", String(receiver.open(f1)))
        assertFalse(runCatching { receiver.open(f1) }.isSuccess)
        val f2 = sender.seal("ikinci".toByteArray()).also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFalse(runCatching { receiver.open(f2) }.isSuccess)
    }

    @Test fun handshakeRejectsOtherFamily() {
        val a = Identity("a", "A", Role.ADMIN, secret)
        val b = Identity("b", "B", Role.CHILD, Crypto.randomBytes(32))
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted: Future<Result<SecureChannel>> = pool.submit<Result<SecureChannel>> {
                server.accept().use { s -> runCatching { SecureChannel.handshake(a, s.getInputStream(), s.getOutputStream()) } }
            }
            Socket(server.inetAddress, server.localPort).use { s ->
                assertFalse(runCatching { SecureChannel.handshake(b, s.getInputStream(), s.getOutputStream()) }.isSuccess)
            }
            assertTrue(accepted.get().isFailure)
        }
        pool.shutdown()
    }

    @Test fun messageIsDeliveredAndAcked() {
        val a = device("Baba", "aaaa")
        val b = device("Ayşe", "bbbb")
        a.manager.onEndpoint(b.identity.deviceId, InetAddress.getLoopbackAddress(), b.manager.port)
        waitUntil { a.online.contains("bbbb") && b.online.contains("aaaa") }

        val sent = a.manager.send("bbbb", "Yemek hazır")
        waitUntil { b.inbox.isNotEmpty() }
        assertEquals("Yemek hazır", b.inbox.single().text)
        assertEquals("aaaa", b.inbox.single().from)
        waitUntil { a.store.messages[sent.id]?.status == MessageStatus.DELIVERED }
    }

    @Test fun messageWaitsInOutboxUntilPeerAppears() {
        val a = device("Baba", "aaaa")
        val sent = a.manager.send("bbbb", "Akşam geç gelirim")
        assertEquals(MessageStatus.PENDING, a.store.messages[sent.id]?.status)

        val b = device("Ayşe", "bbbb")
        // Bu kez kimliği büyük olan cihaz bağlantıyı başlatır.
        b.manager.onEndpoint("aaaa", InetAddress.getLoopbackAddress(), a.manager.port)
        a.manager.onEndpoint("bbbb", InetAddress.getLoopbackAddress(), b.manager.port)
        waitUntil { b.inbox.isNotEmpty() }
        assertEquals("Akşam geç gelirim", b.inbox.single().text)
        waitUntil { a.store.messages[sent.id]?.status == MessageStatus.DELIVERED }
        Thread.sleep(300)
        assertEquals("Mesaj tek kez gösterilmeli", 1, b.inbox.size)
    }

    @Test fun strangerCannotConnect() {
        val a = device("Baba", "aaaa")
        val stranger = device("Yabancı", "0000", Crypto.randomBytes(32))
        stranger.manager.onEndpoint("aaaa", InetAddress.getLoopbackAddress(), a.manager.port)
        Thread.sleep(500)
        assertTrue(a.online.isEmpty())
        assertTrue(stranger.online.isEmpty())
        assertNotNull(a.manager.router)
    }
}
