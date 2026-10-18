package com.ev.iletisim.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * Yerel ağda mDNS ile "ben buradayım" ilanı ve diğer aile cihazlarını bulma.
 * Servis adı "ev-<cihaz kimliği>" biçimindedir; başka aileden cihazlar da görünebilir,
 * onlar el sıkışmada elenir.
 */
class Discovery(
    context: Context,
    private val myId: String,
    private val onFound: (peerId: String, host: InetAddress, port: Int) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = PREFIX + myId
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "İlan edildi: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "İlan hatası $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }.also { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "Keşif hatası $code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                val peerId = peerIdOf(info.serviceName) ?: return
                if (peerId != myId) enqueueResolve(info)
            }
        }.also { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    // NsdManager aynı anda tek çözümleme yapabildiği için sırayla çözülür.
    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.poll()
        if (next == null) {
            resolving = false
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Çözülemedi ${info.serviceName}: $code")
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host
                val peerId = peerIdOf(info.serviceName)
                if (host != null && peerId != null) onFound(peerId, host, info.port)
                resolveNext()
            }
        })
    }

    companion object {
        private const val TAG = "EvKesif"
        private const val SERVICE_TYPE = "_eviletisim._tcp."
        private const val PREFIX = "ev-"

        fun peerIdOf(serviceName: String): String? =
            if (serviceName.startsWith(PREFIX)) serviceName.removePrefix(PREFIX) else null
    }
}
