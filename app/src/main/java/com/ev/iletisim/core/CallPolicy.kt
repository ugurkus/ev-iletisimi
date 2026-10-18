package com.ev.iletisim.core

/** Gün içindeki dakika aralığı; gece yarısını aşabilir (ör. 21:00–07:00). */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute
}

/**
 * Çocuk cihazı arama kuralı: sessiz saatlerde iki çocuk birbirini arayamaz, yetişkinlerle her
 * zaman konuşabilir. Kural hem arayan hem aranan cihazda uygulanır.
 * Sessiz saatler şimdilik sabittir; yönetici ayarı 4. aşamada gelecek.
 */
object CallPolicy {
    val DEFAULT_QUIET_HOURS = QuietHours(21 * 60, 7 * 60)

    fun allows(callerRole: Role, calleeRole: Role, minuteOfDay: Int, quiet: QuietHours = DEFAULT_QUIET_HOURS): Boolean {
        val childToChild = callerRole == Role.CHILD && calleeRole == Role.CHILD
        return !(childToChild && quiet.contains(minuteOfDay))
    }
}
