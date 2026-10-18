package com.ev.iletisim.media

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Tek bir görüntülü görüşmenin WebRTC tarafı. Sadece yerel ağ adayları kullanılır, STUN/TURN
 * sunucusu yoktur; görüntü ve ses DTLS-SRTP ile şifreli olarak cihazdan cihaza akar.
 */
class WebRtcCall(
    context: Context,
    private val sendSignal: (type: String, payload: JSONObject) -> Unit,
    private val onConnectionChange: (connected: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val renderers = mutableListOf<SurfaceViewRenderer>()

    // Karşı tarafın SDP'si gelmeden ulaşan ICE adayları bekletilir.
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    init {
        initializeOnce(appContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        local.init(egl.eglBaseContext, null)
        local.setMirror(true)
        remote.init(egl.eglBaseContext, null)
        renderers += listOf(local, remote)
        remoteRenderer = remote

        val enumerator = Camera2Enumerator(appContext)
        val camera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (camera != null) {
            val cap = enumerator.createCapturer(camera, null)
            val helper = SurfaceTextureHelper.create("ev-kamera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(helper, appContext, source.capturerObserver)
            cap.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            capturer = cap
            textureHelper = helper
            videoSource = source
            videoTrack = factory.createVideoTrack("ev-video", source).also { it.addSink(local) }
        }
        val aSource = factory.createAudioSource(MediaConstraints())
        audioSource = aSource
        audioTrack = factory.createAudioTrack("ev-audio", aSource)

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(config, observer) ?: throw IllegalStateException("WebRTC başlatılamadı")
        videoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        audioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        peer = pc
    }

    /** Arayan taraf çağırır. */
    fun makeOffer() {
        val pc = peer ?: return
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpAdapter(), desc)
                sendSignal("call-offer", JSONObject().put("sdp", desc.description))
            }
        }, MediaConstraints())
    }

    fun onSignal(frame: JSONObject) {
        val pc = peer ?: return
        when (frame.optString("type")) {
            "call-offer" -> {
                val offer = SessionDescription(SessionDescription.Type.OFFER, frame.getString("sdp"))
                pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        onRemoteDescriptionSet()
                        pc.createAnswer(object : SdpAdapter() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                pc.setLocalDescription(SdpAdapter(), desc)
                                sendSignal("call-answer", JSONObject().put("sdp", desc.description))
                            }
                        }, MediaConstraints())
                    }
                }, offer)
            }
            "call-answer" -> {
                val answer = SessionDescription(SessionDescription.Type.ANSWER, frame.getString("sdp"))
                pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() = onRemoteDescriptionSet()
                }, answer)
            }
            "call-ice" -> {
                val c = IceCandidate(frame.getString("mid"), frame.getInt("index"), frame.getString("candidate"))
                synchronized(pendingIce) {
                    if (remoteDescriptionSet) pc.addIceCandidate(c) else pendingIce.add(c)
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    fun setCameraOff(off: Boolean) {
        videoTrack?.setEnabled(!off)
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun close() {
        try { capturer?.stopCapture() } catch (_: InterruptedException) {}
        capturer?.dispose()
        peer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        textureHelper?.dispose()
        renderers.forEach { it.release() }
        factory.dispose()
        egl.release()
        peer = null
    }

    private fun onRemoteDescriptionSet() {
        val pc = peer ?: return
        synchronized(pendingIce) {
            remoteDescriptionSet = true
            pendingIce.forEach { pc.addIceCandidate(it) }
            pendingIce.clear()
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            sendSignal("call-ice", JSONObject().put("mid", c.sdpMid ?: "").put("index", c.sdpMLineIndex).put("candidate", c.sdp))
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) remoteRenderer?.let { track.addSink(it) }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "Bağlantı: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> onConnectionChange(true)
                PeerConnection.PeerConnectionState.FAILED -> onConnectionChange(false)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "SDP oluşturulamadı: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "SDP uygulanamadı: $error")
        }
    }

    companion object {
        private const val TAG = "EvGorusme"
        private const val STREAM_ID = "ev"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        @Volatile private var initialized = false

        @Synchronized
        private fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            initialized = true
        }
    }
}
