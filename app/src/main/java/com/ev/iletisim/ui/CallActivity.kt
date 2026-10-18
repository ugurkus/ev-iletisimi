package com.ev.iletisim.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ev.iletisim.Ev
import com.ev.iletisim.EvService
import com.ev.iletisim.core.Call
import com.ev.iletisim.core.CallManager
import com.ev.iletisim.core.CallState
import com.ev.iletisim.core.EndReason
import com.ev.iletisim.media.WebRtcCall
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Görüntülü ve sesli görüşme ekranı. Arama başlatırken [EXTRA_PEER] ile açılır; gelen aramada
 * bildirimden açılır ve o anki çalan aramayı gösterir.
 */
class CallActivity : Activity(), CallManager.Listener {
    private lateinit var status: TextView
    private lateinit var acceptButton: Button
    private lateinit var controls: LinearLayout
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private var media: WebRtcCall? = null
    private var muted = false
    private var cameraOff = false
    private var speaker = true
    /** false: yalnızca sesli arama. Giden aramada ekrandan, gelende aramanın kendisinden gelir. */
    private var video = true
    private val cameraButtons = mutableListOf<View>()
    private var pendingAccept = false
    private var pendingOutgoing: String? = null
    private val earlySignals = mutableListOf<JSONObject>()

    private val calls get() = Ev.calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = intent.getBooleanExtra(EXTRA_VIDEO, true)
        buildUi()
        setMode(video)

        val calls = calls
        if (calls == null) {
            finish()
            return
        }
        calls.addListener(this)
        val peerToCall = intent.getStringExtra(EXTRA_PEER)
        if (peerToCall != null && savedInstanceState == null) {
            if (hasMediaPermissions()) placeCall(peerToCall) else {
                pendingOutgoing = peerToCall
                requestPermissions(mediaPermissions(), REQ_MEDIA)
            }
        }
        render(calls.state)
    }

    override fun onDestroy() {
        calls?.removeListener(this)
        // Ekran kapanırsa görüşme de biter.
        if (calls?.state !is CallState.Idle) Ev.io.execute { calls?.hangup() }
        stopMedia()
        super.onDestroy()
    }

    override fun onCallState(state: CallState) = runOnUiThread { render(state) }

    override fun onMediaSignal(call: Call, frame: JSONObject) {
        runOnUiThread {
            // Karşı tarafın teklifi, bu cihazın kamerası hazır olmadan gelebilir; kaybolmasın
            val m = media
            if (m == null) earlySignals.add(frame) else m.onSignal(frame)
        }
    }

    private fun render(state: CallState) {
        when (state) {
            is CallState.Idle -> Unit
            is CallState.Ringing -> {
                setMode(state.call.video)
                val name = state.call.peer.name
                val kind = if (state.call.video) "Görüntülü arama" else "Sesli arama"
                status.text = if (state.call.incoming) "$name arıyor…\n$kind" else "$name aranıyor…\n$kind"
                acceptButton.visibility = if (state.call.incoming) android.view.View.VISIBLE else android.view.View.GONE
            }
            is CallState.Active -> {
                setMode(state.call.video)
                cancelRingNotification()
                acceptButton.visibility = android.view.View.GONE
                status.text = "${state.call.peer.name} ile bağlanılıyor…"
                startMedia(state.makesOffer)
            }
            is CallState.Ended -> {
                cancelRingNotification()
                finishWith(state.reason)
            }
        }
    }

    private fun onAcceptPressed() {
        if (!hasMediaPermissions()) {
            pendingAccept = true
            requestPermissions(mediaPermissions(), REQ_MEDIA)
            return
        }
        Ev.io.execute { calls?.accept() }
    }

    private fun startMedia(makesOffer: Boolean) {
        if (media != null) return
        if (!hasMediaPermissions()) {
            requestPermissions(mediaPermissions(), REQ_MEDIA)
            return
        }
        val call = try {
            WebRtcCall(
                this,
                sendSignal = { type, payload -> Ev.io.execute { calls?.sendMedia(type, payload) } },
                onConnectionChange = { connected ->
                    runOnUiThread {
                        if (connected) status.text = if (video) "" else "${(calls?.state as? CallState.Active)?.call?.peer?.name ?: ""}\nSesli görüşme"
                        else Ev.io.execute { calls?.hangup(EndReason.FAILED) }
                    }
                },
                video = video,
            )
        } catch (e: Throwable) {
            failMedia(e)
            return
        }
        try {
            call.start(localView, remoteView)
        } catch (e: Throwable) {
            runCatching { call.close() }
            failMedia(e)
            return
        }
        media = call
        audio(inCall = true)
        controls.visibility = android.view.View.VISIBLE
        if (makesOffer) call.makeOffer()
        earlySignals.forEach { call.onSignal(it) }
        earlySignals.clear()
    }

    /** Kamera ya da WebRTC açılamadıysa nedenini göster; uygulama çökmesin. */
    private fun failMedia(e: Throwable) {
        Log.e(TAG, "Görüşme başlatılamadı", e)
        Toast.makeText(this, "Görüşme başlatılamadı: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        Ev.io.execute { calls?.hangup(EndReason.FAILED) }
    }

    private fun stopMedia() {
        media?.close()
        media = null
        audio(inCall = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_MEDIA) return
        if (!hasMediaPermissions()) {
            Toast.makeText(this, "Görüşme için kamera ve mikrofon izni gerekli", Toast.LENGTH_LONG).show()
            if (pendingOutgoing != null) {
                finish()
                return
            }
            Ev.io.execute { endFromUi() }
            return
        }
        val outgoing = pendingOutgoing
        if (outgoing != null) {
            pendingOutgoing = null
            placeCall(outgoing)
        } else if (pendingAccept) {
            pendingAccept = false
            Ev.io.execute { calls?.accept() }
        } else {
            (calls?.state as? CallState.Active)?.let { startMedia(it.makesOffer) }
        }
    }

    /** Çalan gelen arama reddedilir, diğer her durumda görüşme kapatılır. */
    private fun endFromUi() {
        val c = calls ?: return
        val s = c.state
        if (s is CallState.Ringing && s.call.incoming) c.decline() else c.hangup()
    }

    private fun placeCall(peerId: String) {
        val c = calls ?: return
        Ev.io.execute {
            val failure = c.call(peerId, video)
            if (failure != null) runOnUiThread { finishWith(failure) }
        }
    }

    private fun finishWith(reason: EndReason) {
        if (reason != EndReason.HANGUP && reason != EndReason.REMOTE_HANGUP) {
            Toast.makeText(this, reason.text, Toast.LENGTH_LONG).show()
        }
        stopMedia()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun audio(inCall: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        // Tablet ve telefonlar elde tutulmadan kullanılacağı için hoparlör açık.
        am.isSpeakerphoneOn = inCall && speaker
    }

    /** Sesli aramada görüntü alanları ve kamera düğmeleri gizlenir. */
    private fun setMode(isVideo: Boolean) {
        video = isVideo
        val v = if (isVideo) View.VISIBLE else View.GONE
        localView.visibility = v
        remoteView.visibility = v
        cameraButtons.forEach { it.visibility = v }
    }

    private fun mediaPermissions() =
        if (video) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun hasMediaPermissions() =
        mediaPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun cancelRingNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(EvService.CALL_NOTIFICATION_ID)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        remoteView = SurfaceViewRenderer(this).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
        root.addView(remoteView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val density = resources.displayMetrics.density
        localView = SurfaceViewRenderer(this).apply {
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
        root.addView(
            localView,
            FrameLayout.LayoutParams((120 * density).toInt(), (160 * density).toInt(), Gravity.TOP or Gravity.END).apply {
                setMargins(0, (16 * density).toInt(), (16 * density).toInt(), 0)
            },
        )
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, (24 * density).toInt())
        }
        controls = LinearLayout(this).apply { visibility = android.view.View.GONE }
        controls.addView(button("Sessiz", Look.TONAL) {}.also { b ->
            b.setOnClickListener {
                muted = !muted
                media?.setMuted(muted)
                b.text = if (muted) "Sesi aç" else "Sessiz"
            }
        })
        controls.addView(button("🔊 Hoparlör", Look.TONAL) {}.also { b ->
            b.setOnClickListener {
                speaker = !speaker
                audio(inCall = true)
                b.text = if (speaker) "🔊 Hoparlör" else "📱 Kulaklık"
            }
        })
        controls.addView(button("Kamera kapat", Look.TONAL) {}.also { b ->
            cameraButtons += b
            b.setOnClickListener {
                cameraOff = !cameraOff
                media?.setCameraOff(cameraOff)
                b.text = if (cameraOff) "Kamera aç" else "Kamera kapat"
            }
        })
        controls.addView(button("Çevir", Look.TONAL) { media?.switchCamera() }.also { cameraButtons += it })
        for (i in 0 until controls.childCount) {
            (controls.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(8)
        }
        bottom.addView(controls)

        val actions = LinearLayout(this)
        acceptButton = button("Cevapla") { onAcceptPressed() }.apply { visibility = android.view.View.GONE }
        actions.addView(acceptButton)
        actions.addView(button("Kapat", Look.DANGER) { Ev.io.execute { endFromUi() } })
        for (i in 0 until actions.childCount) {
            actions.getChildAt(i).layoutParams = LinearLayout.LayoutParams(dp(140), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        bottom.addView(actions)
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setBackgroundColor(android.graphics.Color.BLACK)
        fitSystemBars(root)
        setContentView(root)
    }

    companion object {
        private const val TAG = "EvArama"
        const val EXTRA_PEER = "peer"
        private const val REQ_MEDIA = 7
        private const val EXTRA_VIDEO = "video"

        fun callIntent(context: Context, peerId: String, video: Boolean = true) =
            Intent(context, CallActivity::class.java).putExtra(EXTRA_PEER, peerId).putExtra(EXTRA_VIDEO, video)
    }
}
