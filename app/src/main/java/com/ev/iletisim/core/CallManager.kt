package com.ev.iletisim.core

import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** video=false: yalnızca sesli arama, kamera açılmaz. */
data class Call(val id: String, val peer: PeerInfo, val incoming: Boolean, val video: Boolean = true)

enum class EndReason(val text: String) {
    HANGUP("Görüşme bitti"),
    REMOTE_HANGUP("Karşı taraf kapattı"),
    DECLINED("Arama reddedildi"),
    BUSY("Karşı taraf başka bir görüşmede"),
    NO_ANSWER("Cevap yok"),
    QUIET_HOURS("Sessiz saatlerde çocuklar birbirini arayamaz"),
    OFFLINE("Cihaz şu an kapalı"),
    FAILED("Bağlantı kurulamadı"),
}

sealed class CallState {
    object Idle : CallState()
    data class Ringing(val call: Call) : CallState()

    /** Görüntü bağlantısı kuruluyor ya da kuruldu. Teklifi (offer) arayan taraf hazırlar. */
    data class Active(val call: Call) : CallState() {
        val makesOffer get() = !call.incoming
    }

    data class Ended(val call: Call, val reason: EndReason) : CallState()
}

/**
 * Görüntülü görüşmenin zil ve kabul akışı. Görüntü ve ses WebRTC ile cihazlar arasında doğrudan
 * akar; bu sınıf yalnızca kurulum mesajlarını mevcut şifreli bağlantı üzerinden taşır.
 *
 * Akış: call-invite → (zil) → call-accept → call-offer / call-answer / call-ice → call-end.
 * Ret: call-reject {reason: declined | busy | quiet}.
 */
class CallManager(
    private val me: Identity,
    private val transport: (peerId: String, frame: JSONObject) -> Boolean,
    private val peerInfo: (peerId: String) -> PeerInfo?,
    private val minuteOfDay: () -> Int = ::currentMinuteOfDay,
    private val ringTimeoutMs: Long = 30_000,
) {
    interface Listener {
        fun onCallState(state: CallState)

        /** Etkin görüşmeye ait call-offer, call-answer veya call-ice. */
        fun onMediaSignal(call: Call, frame: JSONObject) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var ringTimeout: ScheduledFuture<*>? = null

    @Volatile var state: CallState = CallState.Idle
        private set

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Arama başlatır. Başlatılamazsa nedenini döner. */
    @Synchronized
    fun call(peerId: String, video: Boolean = true): EndReason? {
        if (state !is CallState.Idle) return EndReason.BUSY
        val peer = peerInfo(peerId) ?: return EndReason.OFFLINE
        if (!CallPolicy.allows(me.role, peer.role, minuteOfDay())) return EndReason.QUIET_HOURS
        val call = Call(UUID.randomUUID().toString(), peer, incoming = false, video = video)
        if (!transport(peerId, frame("call-invite", call.id).put("video", video))) return EndReason.OFFLINE
        setState(CallState.Ringing(call))
        startRingTimeout(call)
        return null
    }

    @Synchronized
    fun accept() {
        val s = state as? CallState.Ringing ?: return
        if (!s.call.incoming) return
        if (transport(s.call.peer.deviceId, frame("call-accept", s.call.id))) setState(CallState.Active(s.call))
        else end(s.call, EndReason.OFFLINE, notifyPeer = false)
    }

    @Synchronized
    fun decline() {
        val s = state as? CallState.Ringing ?: return
        if (!s.call.incoming) return
        transport(s.call.peer.deviceId, frame("call-reject", s.call.id).put("reason", "declined"))
        end(s.call, EndReason.DECLINED, notifyPeer = false)
    }

    @Synchronized
    fun hangup(reason: EndReason = EndReason.HANGUP) {
        val call = currentCall() ?: return
        end(call, reason, notifyPeer = true)
    }

    /** Ekran WebRTC teklifini, cevabını ya da ICE adayını karşı tarafa yollar. */
    fun sendMedia(type: String, payload: JSONObject) {
        val s = state as? CallState.Active ?: return
        require(type in MEDIA_TYPES)
        payload.put("type", type).put("call", s.call.id)
        transport(s.call.peer.deviceId, payload)
    }

    @Synchronized
    fun onFrame(peerId: String, frame: JSONObject) {
        val type = frame.optString("type")
        val callId = frame.optString("call")
        if (type == "call-invite") {
            onInvite(peerId, callId, frame.optBoolean("video", true))
            return
        }
        val call = currentCall()
        if (call == null || call.id != callId || call.peer.deviceId != peerId) return
        when (type) {
            "call-accept" -> if (!call.incoming && state is CallState.Ringing) {
                cancelRingTimeout()
                setState(CallState.Active(call))
            }
            "call-reject" -> end(
                call,
                when (frame.optString("reason")) {
                    "busy" -> EndReason.BUSY
                    "quiet" -> EndReason.QUIET_HOURS
                    else -> EndReason.DECLINED
                },
                notifyPeer = false,
            )
            "call-end" -> end(call, EndReason.REMOTE_HANGUP, notifyPeer = false)
            in MEDIA_TYPES -> if (state is CallState.Active) listeners.forEach { it.onMediaSignal(call, frame) }
        }
    }

    /** Kurulum bağlantısı koparsa çalan arama düşer; süren görüşmeyi WebRTC'nin kendisi izler. */
    @Synchronized
    fun onPeerOffline(peerId: String) {
        val s = state as? CallState.Ringing ?: return
        if (s.call.peer.deviceId == peerId) end(s.call, EndReason.OFFLINE, notifyPeer = false)
    }

    fun shutdown() = timer.shutdownNow()

    private fun onInvite(peerId: String, callId: String, video: Boolean) {
        val caller = peerInfo(peerId) ?: return
        val reject = when {
            state !is CallState.Idle -> "busy"
            !CallPolicy.allows(caller.role, me.role, minuteOfDay()) -> "quiet"
            else -> null
        }
        if (reject != null) {
            transport(peerId, frame("call-reject", callId).put("reason", reject))
            return
        }
        val call = Call(callId, caller, incoming = true, video = video)
        setState(CallState.Ringing(call))
        startRingTimeout(call)
    }

    private fun currentCall(): Call? = when (val s = state) {
        is CallState.Ringing -> s.call
        is CallState.Active -> s.call
        else -> null
    }

    private fun end(call: Call, reason: EndReason, notifyPeer: Boolean) {
        cancelRingTimeout()
        if (notifyPeer) transport(call.peer.deviceId, frame("call-end", call.id))
        setState(CallState.Ended(call, reason))
        state = CallState.Idle
    }

    private fun startRingTimeout(call: Call) {
        cancelRingTimeout()
        ringTimeout = timer.schedule({
            synchronized(this) {
                val s = state
                if (s is CallState.Ringing && s.call.id == call.id) {
                    end(call, EndReason.NO_ANSWER, notifyPeer = true)
                }
            }
        }, ringTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelRingTimeout() {
        ringTimeout?.cancel(false)
        ringTimeout = null
    }

    private fun setState(s: CallState) {
        state = s
        listeners.forEach { it.onCallState(s) }
    }

    private fun frame(type: String, callId: String) = JSONObject().put("type", type).put("call", callId)

    companion object {
        val MEDIA_TYPES = setOf("call-offer", "call-answer", "call-ice")

        fun isCallFrame(frame: JSONObject) = frame.optString("type").startsWith("call-")

        private fun currentMinuteOfDay(): Int {
            val c = Calendar.getInstance()
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }
    }
}
