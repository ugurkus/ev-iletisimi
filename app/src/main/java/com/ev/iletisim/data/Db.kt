package com.ev.iletisim.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ev.iletisim.core.ChatMessage
import com.ev.iletisim.core.MessageStatus
import com.ev.iletisim.core.MessageStore
import com.ev.iletisim.core.PeerInfo
import com.ev.iletisim.core.Role

/** Mesajlar ve tanınan aile cihazları. Her cihaz kendi geçmişini tutar. */
class Db(context: Context) : SQLiteOpenHelper(context, "ev.db", null, 1), MessageStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL,
                body TEXT NOT NULL, sent_at INTEGER NOT NULL, status TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX messages_pending ON messages(recipient, status)")
        db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun saveOutgoing(m: ChatMessage) {
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun saveIncoming(m: ChatMessage): Boolean =
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    override fun pendingFor(peerId: String): List<ChatMessage> = query(
        "SELECT * FROM messages WHERE recipient = ? AND status = ? ORDER BY sent_at",
        peerId, MessageStatus.PENDING.name,
    )

    override fun markDelivered(id: String) {
        val cv = ContentValues().apply { put("status", MessageStatus.DELIVERED.name) }
        writableDatabase.update("messages", cv, "id = ? AND status = ?", arrayOf(id, MessageStatus.PENDING.name))
    }

    fun conversation(peerId: String): List<ChatMessage> = query(
        "SELECT * FROM messages WHERE sender = ? OR recipient = ? ORDER BY sent_at",
        peerId, peerId,
    )

    fun saveContact(p: PeerInfo) {
        val cv = ContentValues().apply {
            put("id", p.deviceId)
            put("name", p.name)
            put("role", p.role.wire)
        }
        writableDatabase.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun contacts(): List<PeerInfo> =
        readableDatabase.rawQuery("SELECT id, name, role FROM contacts ORDER BY name", null).use { c ->
            buildList {
                while (c.moveToNext()) add(PeerInfo(c.getString(0), c.getString(1), Role.fromWire(c.getString(2))))
            }
        }

    fun contact(id: String): PeerInfo? = contacts().firstOrNull { it.deviceId == id }

    private fun query(sql: String, vararg args: String): List<ChatMessage> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChatMessage(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            from = c.getString(c.getColumnIndexOrThrow("sender")),
                            to = c.getString(c.getColumnIndexOrThrow("recipient")),
                            text = c.getString(c.getColumnIndexOrThrow("body")),
                            sentAt = c.getLong(c.getColumnIndexOrThrow("sent_at")),
                            status = MessageStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))),
                        )
                    )
                }
            }
        }

    private fun values(m: ChatMessage) = ContentValues().apply {
        put("id", m.id)
        put("sender", m.from)
        put("recipient", m.to)
        put("body", m.text)
        put("sent_at", m.sentAt)
        put("status", m.status.name)
    }
}
