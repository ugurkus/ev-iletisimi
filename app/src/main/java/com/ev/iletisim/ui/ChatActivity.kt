package com.ev.iletisim.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.ev.iletisim.Ev
import com.ev.iletisim.core.ChatMessage
import com.ev.iletisim.core.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Bir cihazla birebir sohbet. */
class ChatActivity : Activity() {
    private lateinit var peerId: String
    private lateinit var list: ListView
    private val refresh: () -> Unit = { render() }
    private val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerId = intent.getStringExtra(EXTRA_PEER) ?: return finish()
        val peer = Ev.db.contact(peerId)
        val root = column()
        val header = LinearLayout(this)
        header.addView(title(peer?.name ?: "Sohbet"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Görüntülü ara") { startActivity(CallActivity.callIntent(this, peerId)) })
        root.addView(header)
        list = ListView(this).apply {
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this)
        val input = EditText(this).apply { hint = "Mesaj yaz" }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Gönder") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                send(text)
            }
        })
        root.addView(row)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        openPeerId = peerId
        Ev.addListener(refresh)
        render()
    }

    override fun onPause() {
        openPeerId = null
        Ev.removeListener(refresh)
        super.onPause()
    }

    private fun send(text: String) {
        val me = Ev.identity ?: return
        Ev.io.execute {
            // Servis henüz açılmadıysa mesaj giden kutusuna yazılır, bağlantı kurulunca gider.
            Ev.peers?.send(peerId, text) ?: Ev.db.saveOutgoing(
                ChatMessage(UUID.randomUUID().toString(), me.deviceId, peerId, text, System.currentTimeMillis(), MessageStatus.PENDING)
            )
            Ev.notifyChanged()
        }
    }

    private fun render() {
        val myId = Ev.identity?.deviceId
        val lines = Ev.db.conversation(peerId).map { m ->
            val mark = when (m.status) {
                MessageStatus.PENDING -> "  ⏳"
                MessageStatus.DELIVERED -> "  ✓"
                MessageStatus.RECEIVED -> ""
            }
            val who = if (m.from == myId) "Sen" else "O"
            "${time.format(Date(m.sentAt))}  $who: ${m.text}$mark"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    companion object {
        const val EXTRA_PEER = "peer"

        /** Açık sohbetten gelen mesaj için bildirim gösterilmez. */
        @Volatile var openPeerId: String? = null
    }
}
