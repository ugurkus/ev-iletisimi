package com.ev.iletisim.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Uygulamanın kendisini diğer cihazlara verir: ya ev Wi‑Fi'ında küçük bir
 * indirme adresi açıp QR ile gösterir, ya da Android paylaşım menüsüne gönderir.
 * Adres yalnızca bu ekran açıkken çalışır.
 */
class ShareActivity : Activity() {
    private var server: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apk = copyApk()

        val root = column()
        root.addView(title("Uygulamayı paylaş"))
        val ip = wifiAddress()
        val qr = ImageView(this)
        if (ip == null) {
            root.addView(text("Wi‑Fi'a bağlı değilsiniz. Aşağıdaki düğmeyle gönderebilirsiniz."))
        } else {
            val url = "http://$ip:$PORT/ev-iletisimi.apk"
            serve(apk)
            root.addView(text("Yeni cihaz aynı Wi‑Fi'da olsun. Kamerasıyla bu QR'ı okutun ya da tarayıcıya yazın:"))
            root.addView(text(url))
            qr.setImageBitmap(qrBitmap(url))
        }
        root.addView(button("Bluetooth / Quick Share ile gönder") { shareApk(apk) })
        root.addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fitSystemBars(root)
        setContentView(root)
    }

    override fun onDestroy() {
        server?.close()
        super.onDestroy()
    }

    private fun copyApk(): File {
        val dir = File(cacheDir, "paylas").apply { mkdirs() }
        val out = File(dir, "ev-iletisimi.apk")
        File(applicationInfo.sourceDir).copyTo(out, overwrite = true)
        return out
    }

    private fun shareApk(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.paylas", apk)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Uygulamayı gönder"))
    }

    private fun serve(apk: File) {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "İndirme adresi açılamadı", Toast.LENGTH_LONG).show()
            return
        }
        server = socket
        thread(isDaemon = true, name = "apk-paylas") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) {
                    client.use { c ->
                        // İsteğin ne olduğu önemli değil; her isteğe APK'yı ver
                        c.getInputStream().bufferedReader().readLine()
                        val out = c.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.android.package-archive\r\n" +
                                "Content-Disposition: attachment; filename=\"ev-iletisimi.apk\"\r\n" +
                                "Content-Length: ${apk.length()}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(),
                        )
                        apk.inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                }
            }
        }
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

    companion object {
        const val PORT = 8765
    }
}
