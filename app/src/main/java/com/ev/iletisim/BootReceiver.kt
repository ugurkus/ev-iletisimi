package com.ev.iletisim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cihaz yeniden başlayınca, aileye bağlıysa servisi kendiliğinden açar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Ev.identity != null) EvService.start(context)
    }
}
