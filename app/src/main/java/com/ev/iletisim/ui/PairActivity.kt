package com.ev.iletisim.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.ev.iletisim.Ev
import com.ev.iletisim.core.PairingInvite
import com.ev.iletisim.core.Role
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Sadece yetişkin telefonlarda: yeni cihaz için ad ve rol seçilir, QR gösterilir. */
class PairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = Ev.identity
        if (me == null || me.role != Role.ADMIN) {
            finish()
            return
        }
        // QR aile sırrını taşır; ekran görüntüsü alınmasın.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val root = column()
        root.addView(title("Yeni cihaz ekle"))
        val name = EditText(this).apply { hint = "Cihazın adı (örn. Ayşe tablet)" }
        root.addView(name)
        val roles = RadioGroup(this)
        val child = RadioButton(this).apply { text = Role.CHILD.label; id = 1; isChecked = true }
        val adult = RadioButton(this).apply { text = Role.ADMIN.label; id = 2 }
        roles.addView(child)
        roles.addView(adult)
        root.addView(roles)
        val qr = ImageView(this)
        root.addView(button("QR oluştur") {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) {
                val role = if (adult.isChecked) Role.ADMIN else Role.CHILD
                val port = Ev.peers?.port ?: -1
                val hint = wifiAddress()?.takeIf { port > 0 }?.let { PairingInvite.Hint(me.deviceId, it, port) }
                qr.setImageBitmap(render(PairingInvite(me.familySecret, n, role, hint).encode()))
            }
        })
        root.addView(text("Yeni cihazda \"Aileye katıl\" deyip bu QR'ı okutun."))
        root.addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fitSystemBars(root)
        setContentView(root)
    }

    private fun render(content: String, size: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
