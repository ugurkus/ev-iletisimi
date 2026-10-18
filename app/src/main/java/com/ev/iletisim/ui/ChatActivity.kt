package com.ev.iletisim.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
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
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(title(peer?.name ?: "Sohbet"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            button("📞 Sesli", Look.TONAL) { startActivity(CallActivity.callIntent(this, peerId, video = false)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) },
        )
        header.addView(button("📹 Görüntülü", Look.TONAL) { startActivity(CallActivity.callIntent(this, peerId, video = true)) })
        root.addView(header)
        list = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this)
        val pal = Palette(this)
        val input = EditText(this).apply {
            hint = "Mesaj yaz"
            textSize = 17f
            setTextColor(pal.text)
            setHintTextColor(pal.subtle)
            background = rounded(pal.surface, 28)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minHeight = dp(56)
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(button("Gönder") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                send(text)
            }
        })
        root.addView(row)
        fitSystemBars(root)
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
        val messages = Ev.db.conversation(peerId)
        list.adapter = object : ArrayAdapter<ChatMessage>(this, 0, messages) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                bubble(messages[position], messages[position].from == myId)
        }
    }

    /** Benim mesajlarım sağda vurgu renginde, karşı tarafınkiler solda nötr balonda. */
    private fun bubble(m: ChatMessage, mine: Boolean): View {
        val pal = Palette(this)
        val mark = when (m.status) {
            MessageStatus.PENDING -> "  ⏳"
            MessageStatus.DELIVERED -> "  ✓"
            MessageStatus.RECEIVED -> ""
        }
        val body = TextView(this).apply {
            text = m.text
            textSize = 17f
            setTextColor(if (mine) pal.onPrimary else pal.text)
            background = rounded(if (mine) pal.primary else pal.surface, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
        }
        val meta = TextView(this).apply {
            text = time.format(Date(m.sentAt)) + mark
            textSize = 12f
            setTextColor(pal.subtle)
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(body)
            addView(meta)
        }
    }

    companion object {
        const val EXTRA_PEER = "peer"

        /** Açık sohbetten gelen mesaj için bildirim gösterilmez. */
        @Volatile var openPeerId: String? = null
    }
}
