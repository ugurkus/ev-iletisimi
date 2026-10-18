package com.ev.iletisim.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import com.ev.iletisim.update.ApkServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Uygulamanın kendisini diğer cihazlara verir: ya ev Wi‑Fi'ında küçük bir
 * indirme adresi açıp QR ile gösterir, ya da Android paylaşım menüsüne gönderir.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column()
        root.addView(title("Uygulamayı paylaş"))
        val ip = wifiAddress()
        val qr = ImageView(this)
        if (ip == null || !ApkServer.start(this)) {
            root.addView(text("Wi‑Fi'a bağlı değilsiniz. Aşağıdaki düğmeyle gönderebilirsiniz."))
        } else {
            val url = "http://$ip:${ApkServer.PORT}/ev-iletisimi.apk"
            root.addView(text("Yeni cihaz aynı Wi‑Fi'da olsun. Kamerasıyla bu QR'ı okutun ya da tarayıcıya yazın:"))
            root.addView(text(url))
            qr.setImageBitmap(qrBitmap(url))
        }
        root.addView(button("Bluetooth / Quick Share ile gönder") { shareApk() })
        root.addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fitSystemBars(root)
        setContentView(root)
    }

    private fun shareApk() {
        // Paylaşım menüsü dosyayı ancak FileProvider üzerinden okuyabilir; kopyası verilir
        val dir = File(cacheDir, "paylas").apply { mkdirs() }
        val apk = File(dir, "ev-iletisimi.apk")
        File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.paylas", apk)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Uygulamayı gönder"))
    }

    private fun wifiAddress(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

    private fun qrBitmap(content: String, size: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
