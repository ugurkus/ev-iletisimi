package com.ev.iletisim.net

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * mDNS'in yanında ikinci keşif yolu: bazı modemler ve telefonlar mDNS paketlerini
 * yutuyor. Her cihaz birkaç saniyede bir yerel ağa "ben buradayım" yayını yapar.
 * Pakette aile sırrı yoktur; yalnızca sırdan türetilmiş kısa aile kimliği vardır.
 * Gerçek doğrulama yine el sıkışmada yapılır.
 */
class BroadcastDiscovery(
    private val myId: String,
    private val familyId: String,
    private val onFound: (peerId: String, host: InetAddress, port: Int) -> Unit,
) {
    @Volatile private var socket: DatagramSocket? = null

    fun start(port: Int) {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Yayın soketi açılamadı", e)
            return
        }
        socket = s
        thread(isDaemon = true, name = "ev-yayin-dinle") {
            val buf = ByteArray(512)
            while (!s.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try { s.receive(packet) } catch (e: IOException) { break }
                val parsed = parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                if (parsed.family == familyId && parsed.deviceId != myId) {
                    onFound(parsed.deviceId, packet.address, parsed.port)
                }
            }
        }
        thread(isDaemon = true, name = "ev-yayin-gonder") {
            val bytes = encode(familyId, myId, port).toByteArray(Charsets.UTF_8)
            while (!s.isClosed) {
                for (target in targets()) {
                    try { s.send(DatagramPacket(bytes, bytes.size, target, UDP_PORT)) } catch (_: IOException) {}
                }
                try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    /** Genel yayın adresi ve her ağ arayüzünün kendi yayın adresi. */
    private fun targets(): List<InetAddress> {
        val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNullTo(list) { it.broadcast }
        } catch (_: IOException) {}
        return list.distinct()
    }

    data class Announcement(val family: String, val deviceId: String, val port: Int)

    companion object {
        private const val TAG = "EvYayin"
        const val UDP_PORT = 47101
        private const val INTERVAL_MS = 5_000L
        private const val PREFIX = "evb1"

        fun encode(familyId: String, deviceId: String, port: Int) = "$PREFIX|$familyId|$deviceId|$port"

        fun parse(text: String): Announcement? {
            val parts = text.split("|")
            if (parts.size != 4 || parts[0] != PREFIX) return null
            val port = parts[3].toIntOrNull() ?: return null
            if (port !in 1..65535 || parts[2].isEmpty()) return null
            return Announcement(parts[1], parts[2], port)
        }
    }
}
