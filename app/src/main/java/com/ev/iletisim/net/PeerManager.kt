package com.ev.iletisim.net

import com.ev.iletisim.core.CallManager
import com.ev.iletisim.core.Identity
import com.ev.iletisim.core.MessageRouter
import com.ev.iletisim.core.MessageStore
import com.ev.iletisim.core.PeerInfo
import com.ev.iletisim.core.SecureChannel
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Her cihazda çalışan eş yöneticisi: gelen bağlantıları kabul eder, keşfedilen cihazlara bağlanır
 * ve mesajları [MessageRouter]'a aktarır. Android'e bağımlı değildir; keşif dışarıdan
 * [onEndpoint] ile beslenir, böylece masaüstünde de test edilebilir.
 *
 * İki cihaz aynı anda birbirine bağlanırsa, ikisi de "kimliği küçük olanın açtığı bağlantıyı
 * tut" kuralını uygular ve aynı bağlantıda buluşur.
 */
class PeerManager(
    private val me: Identity,
    store: MessageStore,
    private val events: Events,
    private val log: (String) -> Unit = {},
) {
    interface Events : MessageRouter.Listener {
        fun onPeerOnline(peer: PeerInfo)
        fun onPeerOffline(peerId: String)
    }

    private class Endpoint(val host: InetAddress, val port: Int, val seenAt: Long)

    private inner class Connection(val socket: Socket, val channel: SecureChannel, val dialerId: String) {
        val peerId get() = channel.peer.deviceId

        fun close() = try { socket.close() } catch (_: IOException) {}
    }

    val router = MessageRouter(me.deviceId, store, ::sendFrame, events)

    /** "call-" ile başlayan çerçeveler mesaj yerine görüntülü görüşmeye gider. */
    @Volatile var callHandler: ((peerId: String, frame: JSONObject) -> Unit)? = null

    private val connections = ConcurrentHashMap<String, Connection>()
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val workers = Executors.newCachedThreadPool()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** Dinlenen port; keşif servisi bunu ilan eder. */
    val port: Int get() = server?.localPort ?: -1

    fun start(bindAddress: InetAddress? = null) {
        running = true
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, 0))
        server = s
        workers.execute { acceptLoop(s) }
        timer.scheduleWithFixedDelay(::maintenance, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS)
        log("Dinleniyor: port ${s.localPort}")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        connections.values.forEach { it.close() }
        connections.clear()
        timer.shutdownNow()
        workers.shutdownNow()
    }

    fun isOnline(peerId: String) = connections.containsKey(peerId)

    /** Şu an bağlı cihazın el sıkışmada bildirdiği bilgi. */
    fun peerInfo(peerId: String): PeerInfo? = connections[peerId]?.channel?.peer

    /** Keşif bir aile cihazını bulduğunda çağrılır. */
    fun onEndpoint(peerId: String, host: InetAddress, port: Int) {
        if (peerId == me.deviceId) return
        endpoints[peerId] = Endpoint(host, port, System.currentTimeMillis())
        // Kimliği küçük olan hemen bağlanır; diğeri biraz bekler, gerekirse bakım döngüsünde bağlanır.
        if (me.deviceId < peerId) dial(peerId)
    }

    fun send(to: String, text: String) = router.send(to, text)

    fun sendFrame(peerId: String, frame: JSONObject): Boolean {
        val c = connections[peerId] ?: return false
        return try {
            c.channel.send(frame)
            true
        } catch (e: IOException) {
            drop(c)
            false
        }
    }

    private fun maintenance() {
        val now = System.currentTimeMillis()
        for ((peerId, ep) in endpoints) {
            if (connections.containsKey(peerId)) continue
            if (me.deviceId < peerId || now - ep.seenAt > GRACE_MS) dial(peerId)
        }
        for (c in connections.values) sendFrame(c.peerId, JSONObject().put("type", "ping"))
    }

    private fun dial(peerId: String) {
        val ep = endpoints[peerId] ?: return
        if (!running || connections.containsKey(peerId) || !dialing.add(peerId)) return
        workers.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS)
                setUp(socket, dialerId = me.deviceId)
            } catch (e: IOException) {
                log("Bağlanılamadı ${peerId.take(8)}: ${e.message}")
            } finally {
                dialing.remove(peerId)
            }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try { s.accept() } catch (e: IOException) { break }
            workers.execute {
                try {
                    setUp(socket, dialerId = null)
                } catch (e: IOException) {
                    log("Gelen bağlantı reddedildi: ${e.message}")
                    try { socket.close() } catch (_: IOException) {}
                }
            }
        }
    }

    private fun setUp(socket: Socket, dialerId: String?) {
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        val channel = try {
            SecureChannel.handshake(me, socket.getInputStream(), socket.getOutputStream())
        } catch (e: Exception) {
            socket.close()
            throw (e as? IOException ?: IOException(e.message, e))
        }
        val peer = channel.peer
        val conn = Connection(socket, channel, dialerId ?: peer.deviceId)
        if (!register(conn)) return
        log("Bağlandı: ${peer.name}")
        events.onPeerOnline(peer)
        router.onPeerConnected(peer.deviceId)
        readLoop(conn)
    }

    /** Aynı cihaza ikinci bağlantı gelirse hangisinin kalacağına karar verir. */
    @Synchronized
    private fun register(conn: Connection): Boolean {
        val existing = connections[conn.peerId]
        if (existing != null) {
            val preferredDialer = minOf(me.deviceId, conn.peerId)
            val keepExisting = existing.dialerId == preferredDialer && conn.dialerId != preferredDialer
            if (keepExisting) {
                conn.close()
                return false
            }
            existing.close()
        }
        connections[conn.peerId] = conn
        return true
    }

    private fun readLoop(conn: Connection) {
        try {
            while (running) {
                val frame = conn.channel.receive()
                when {
                    frame.optString("type") == "ping" -> Unit
                    CallManager.isCallFrame(frame) -> callHandler?.invoke(conn.peerId, frame)
                    else -> router.onFrame(conn.peerId, frame)
                }
            }
        } catch (e: Exception) {
            log("Bağlantı koptu ${conn.channel.peer.name}: ${e.message}")
        } finally {
            drop(conn)
        }
    }

    private fun drop(conn: Connection) {
        conn.close()
        if (connections.remove(conn.peerId, conn)) events.onPeerOffline(conn.peerId)
    }

    companion object {
        private const val RETRY_SECONDS = 10L
        private const val GRACE_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
