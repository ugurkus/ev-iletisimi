package com.ev.iletisim.core

import com.ev.iletisim.net.PeerManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class CallTest {
    private val secret = Crypto.randomBytes(32)
    private val nodes = mutableListOf<Node>()
    private var minute = 12 * 60

    inner class Node(name: String, id: String, role: Role, ringMs: Long = 2000) : CallManager.Listener {
        val device = Device(name, role, secret, id)
        val calls = CallManager(device.identity, device.manager::sendFrame, device.manager::peerInfo, { minute }, ringMs)
        val states = CopyOnWriteArrayList<CallState>()
        val media = CopyOnWriteArrayList<JSONObject>()

        init {
            calls.addListener(this)
            device.manager.callHandler = calls::onFrame
            device.start()
            nodes.add(this)
        }

        override fun onCallState(state: CallState) { states.add(state) }
        override fun onMediaSignal(call: Call, frame: JSONObject) { media.add(frame) }
        val lastEnd get() = states.filterIsInstance<CallState.Ended>().lastOrNull()?.reason
    }

    @After fun tearDown() = nodes.forEach { it.calls.shutdown(); it.device.manager.stop() }

    private fun connect(a: Node, b: Node) {
        a.device.manager.onEndpoint(b.device.identity.deviceId, InetAddress.getLoopbackAddress(), b.device.manager.port)
        b.device.manager.onEndpoint(a.device.identity.deviceId, InetAddress.getLoopbackAddress(), a.device.manager.port)
        waitUntil { a.device.online.isNotEmpty() && b.device.online.isNotEmpty() }
    }

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Zaman aşımı")
            Thread.sleep(20)
        }
    }

    @Test fun quietHoursSpanMidnight() {
        val q = CallPolicy.DEFAULT_QUIET_HOURS
        assertTrue(q.contains(22 * 60))
        assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60))
        assertFalse(q.contains(20 * 60 + 59))
    }

    @Test fun policyOnlyBlocksChildToChildAtNight() {
        val night = 22 * 60
        val day = 15 * 60
        assertFalse(CallPolicy.allows(Role.CHILD, Role.CHILD, night))
        assertTrue(CallPolicy.allows(Role.CHILD, Role.ADMIN, night))
        assertTrue(CallPolicy.allows(Role.ADMIN, Role.CHILD, night))
        assertTrue(CallPolicy.allows(Role.CHILD, Role.CHILD, day))
    }

    @Test fun callIsAcceptedAndMediaSignalsFlowBothWays() {
        val dad = Node("Baba", "aaaa", Role.ADMIN)
        val kid = Node("Ayşe", "bbbb", Role.CHILD)
        connect(dad, kid)

        assertNull(dad.calls.call("bbbb"))
        waitUntil { kid.calls.state is CallState.Ringing }
        val ringing = kid.calls.state as CallState.Ringing
        assertTrue(ringing.call.incoming)
        assertEquals("Baba", ringing.call.peer.name)

        kid.calls.accept()
        waitUntil { dad.calls.state is CallState.Active }
        assertTrue((dad.calls.state as CallState.Active).makesOffer)
        assertFalse((kid.calls.state as CallState.Active).makesOffer)

        dad.calls.sendMedia("call-offer", JSONObject().put("sdp", "v=0 teklif"))
        waitUntil { kid.media.isNotEmpty() }
        assertEquals("v=0 teklif", kid.media.single().getString("sdp"))
        kid.calls.sendMedia("call-answer", JSONObject().put("sdp", "v=0 cevap"))
        waitUntil { dad.media.isNotEmpty() }
        assertEquals("call-answer", dad.media.single().getString("type"))

        kid.calls.hangup()
        waitUntil { dad.calls.state is CallState.Idle }
        assertEquals(EndReason.REMOTE_HANGUP, dad.lastEnd)
        assertEquals(EndReason.HANGUP, kid.lastEnd)
    }

    @Test fun childrenCannotCallEachOtherAtNightEvenIfCallerIsModified() {
        minute = 23 * 60
        val ali = Node("Ali", "aaaa", Role.CHILD)
        val ayse = Node("Ayşe", "bbbb", Role.CHILD)
        connect(ali, ayse)
        assertEquals(EndReason.QUIET_HOURS, ali.calls.call("bbbb"))

        // Arayan kuralı atlasa bile aranan cihaz reddeder.
        ali.device.manager.sendFrame("bbbb", JSONObject().put("type", "call-invite").put("call", "x"))
        Thread.sleep(300)
        assertTrue(ayse.calls.state is CallState.Idle)
        assertTrue(ayse.states.isEmpty())
    }

    @Test fun childCanCallParentAtNight() {
        minute = 23 * 60
        val kid = Node("Ayşe", "aaaa", Role.CHILD)
        val mom = Node("Anne", "bbbb", Role.ADMIN)
        connect(kid, mom)
        assertNull(kid.calls.call("bbbb"))
        waitUntil { mom.calls.state is CallState.Ringing }
    }

    @Test fun declineBusyAndNoAnswer() {
        val dad = Node("Baba", "aaaa", Role.ADMIN, ringMs = 400)
        val kid = Node("Ayşe", "bbbb", Role.CHILD, ringMs = 3000)
        val mom = Node("Anne", "cccc", Role.ADMIN, ringMs = 400)
        connect(dad, kid)
        connect(mom, kid)

        assertNull(dad.calls.call("bbbb"))
        waitUntil { kid.calls.state is CallState.Ringing }
        // Ayşe zaten aranırken annenin araması meşgule düşer.
        assertNull(mom.calls.call("bbbb"))
        waitUntil { mom.lastEnd != null }
        assertEquals(EndReason.BUSY, mom.lastEnd)

        kid.calls.decline()
        waitUntil { dad.lastEnd != null }
        assertEquals(EndReason.DECLINED, dad.lastEnd)

        assertNull(mom.calls.call("bbbb"))
        waitUntil(3000) { mom.calls.state is CallState.Idle && kid.calls.state is CallState.Idle && kid.states.size >= 4 }
        assertEquals(EndReason.NO_ANSWER, mom.lastEnd)
    }

    @Test fun callingOfflineDeviceFailsImmediately() {
        val dad = Node("Baba", "aaaa", Role.ADMIN)
        assertEquals(EndReason.OFFLINE, dad.calls.call("bbbb"))
    }
}
