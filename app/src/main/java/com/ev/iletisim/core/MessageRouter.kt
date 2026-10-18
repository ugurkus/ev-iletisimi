package com.ev.iletisim.core

import org.json.JSONObject
import java.util.UUID

enum class MessageStatus { PENDING, DELIVERED, RECEIVED }

data class ChatMessage(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val sentAt: Long,
    val status: MessageStatus,
)

/** Mesajların kalıcı deposu. Android'de SQLite, testlerde bellek içi. */
interface MessageStore {
    fun saveOutgoing(m: ChatMessage)
    fun pendingFor(peerId: String): List<ChatMessage>
    fun markDelivered(id: String)

    /** Mesaj daha önce alınmışsa false döner. */
    fun saveIncoming(m: ChatMessage): Boolean
}

/**
 * Merkezsiz mesaj iletimi: mesaj önce gönderenin giden kutusuna yazılır, alıcı bağlıysa hemen
 * gider. Alıcı "ack" dönene kadar PENDING kalır ve alıcı her bağlandığında yeniden yollanır.
 * Alıcı aynı mesajı iki kez alırsa kimliğinden tanır ve bir kez gösterir.
 */
class MessageRouter(
    private val myId: String,
    private val store: MessageStore,
    private val transport: (peerId: String, frame: JSONObject) -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onIncoming(m: ChatMessage)
        fun onDelivered(messageId: String)
    }

    fun send(to: String, text: String, now: Long = System.currentTimeMillis()): ChatMessage {
        val m = ChatMessage(UUID.randomUUID().toString(), myId, to, text, now, MessageStatus.PENDING)
        store.saveOutgoing(m)
        transport(to, toFrame(m))
        return m
    }

    fun onPeerConnected(peerId: String) {
        for (m in store.pendingFor(peerId)) {
            if (!transport(peerId, toFrame(m))) break
        }
    }

    /** [peerId] el sıkışmada doğrulanmış kimliktir; mesajdaki "from" ona uymalıdır. */
    fun onFrame(peerId: String, frame: JSONObject) {
        when (frame.optString("type")) {
            "msg" -> {
                if (frame.optString("from") != peerId || frame.optString("to") != myId) return
                val m = ChatMessage(
                    id = frame.getString("id"),
                    from = peerId,
                    to = myId,
                    text = frame.getString("text"),
                    sentAt = frame.optLong("ts"),
                    status = MessageStatus.RECEIVED,
                )
                val isNew = store.saveIncoming(m)
                transport(peerId, JSONObject().put("type", "ack").put("id", m.id))
                if (isNew) listener.onIncoming(m)
            }
            "ack" -> {
                val id = frame.getString("id")
                store.markDelivered(id)
                listener.onDelivered(id)
            }
        }
    }

    private fun toFrame(m: ChatMessage) = JSONObject()
        .put("type", "msg")
        .put("id", m.id)
        .put("from", m.from)
        .put("to", m.to)
        .put("text", m.text)
        .put("ts", m.sentAt)
}
