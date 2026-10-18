package com.ev.iletisim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.InetAddress
import com.ev.iletisim.core.CallManager
import com.ev.iletisim.core.CallState
import com.ev.iletisim.core.ChatMessage
import com.ev.iletisim.core.PeerInfo
import com.ev.iletisim.core.Crypto
import com.ev.iletisim.net.BroadcastDiscovery
import com.ev.iletisim.net.Discovery
import com.ev.iletisim.net.PeerManager
import com.ev.iletisim.ui.CallActivity
import com.ev.iletisim.ui.ChatActivity
import com.ev.iletisim.ui.MainActivity
import com.ev.iletisim.update.ApkServer

/**
 * Uygulama kapalıyken de mesaj alabilmek için sürekli çalışan ön plan servisi.
 * Telefonun pil ayarlarında uygulama "kısıtlamasız" olmalıdır, yoksa uykuda bağlantı kopar.
 */
class EvService : Service(), PeerManager.Events, CallManager.Listener {
    private var discovery: Discovery? = null
    private var broadcast: BroadcastDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("Ev İletişimi açık")
            .setContentText("Evdeki cihazlardan mesaj bekleniyor")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openApp(Intent(this, MainActivity::class.java)))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }

        val me = Ev.identity ?: run { stopSelf(); return }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("ev-kesif").apply { setReferenceCounted(false); acquire() }

        val manager = PeerManager(me, Ev.db, this) { Log.i(TAG, it) }
        val calls = CallManager(me, manager::sendFrame, manager::peerInfo)
        calls.addListener(this)
        manager.callHandler = calls::onFrame
        Ev.peers = manager
        Ev.calls = calls
        Ev.io.execute {
            try {
                manager.start(port = PeerManager.PREFERRED_PORT)
            } catch (e: java.io.IOException) {
                Ev.netProblem = "Ağ dinlenemiyor: ${e.message}"
                Ev.notifyChanged()
                return@execute
            }
            ApkServer.start(this)
            discovery = Discovery(this, me.deviceId, manager::onEndpoint).also { it.start(manager.port) }
            broadcast = BroadcastDiscovery(me.deviceId, Crypto.familyId(me.familySecret), manager::onEndpoint)
                .also { it.start(manager.port) }
            // Keşif hiç çalışmasa bile QR'ı gösteren cihaza doğrudan bağlanmayı dene
            Ev.identities.inviterHint()?.let { hint ->
                runCatching { InetAddress.getByName(hint.host) }.getOrNull()
                    ?.let { manager.onEndpoint(hint.deviceId, it, hint.port) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        discovery?.stop()
        broadcast?.stop()
        Ev.calls?.shutdown()
        Ev.calls = null
        Ev.peers?.stop()
        Ev.peers = null
        multicastLock?.release()
        super.onDestroy()
    }

    override fun onPeerOnline(peer: PeerInfo) {
        Ev.db.saveContact(peer)
        Ev.notifyChanged()
    }

    override fun onPeerOffline(peerId: String) {
        Ev.calls?.onPeerOffline(peerId)
        Ev.notifyChanged()
    }

    /** Gelen aramada zil çalan, kilit ekranında da açılan bildirim gösterilir. */
    override fun onCallState(state: CallState) {
        val nm = getSystemService(NotificationManager::class.java)!!
        if (state !is CallState.Ringing || !state.call.incoming) {
            if (state !is CallState.Ringing) nm.cancel(CALL_NOTIFICATION_ID)
            return
        }
        val screen = openApp(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val n = Notification.Builder(this, CHANNEL_CALLS)
            .setContentTitle("${state.call.peer.name} arıyor")
            .setContentText(if (state.call.video) "Görüntülü arama" else "Sesli arama")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .build()
        nm.notify(CALL_NOTIFICATION_ID, n)
    }

    override fun onDelivered(messageId: String) {
        Ev.notifyChanged()
    }

    override fun onIncoming(m: ChatMessage) {
        Ev.notifyChanged()
        if (ChatActivity.openPeerId == m.from) return
        val sender = Ev.db.contact(m.from)?.name ?: "Aile"
        val intent = Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, m.from)
        val n = Notification.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(sender)
            .setContentText(m.text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openApp(intent))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)!!.notify(m.from.hashCode(), n)
    }

    private fun openApp(intent: Intent) = PendingIntent.getActivity(
        this, intent.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Arka plan", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Mesajlar", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Aramalar", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
            }
        )
    }

    companion object {
        private const val TAG = "EvServis"
        private const val CHANNEL_SERVICE = "servis"
        private const val CHANNEL_MESSAGES = "mesajlar"
        private const val CHANNEL_CALLS = "aramalar"
        const val CALL_NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EvService::class.java))
        }

        fun restart(context: Context) {
            context.stopService(Intent(context, EvService::class.java))
            start(context)
        }
    }
}
