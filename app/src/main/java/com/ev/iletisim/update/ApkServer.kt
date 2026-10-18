package com.ev.iletisim.update

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Ev Wi‑Fi'ında bu cihazdaki uygulamayı dağıtan küçük indirme sunucusu.
 * "/version" sürüm numarasını, diğer her adres APK'nın kendisini verir.
 * Hem "Uygulamayı paylaş" ekranı hem de diğer cihazların güncelleme denetimi bunu kullanır.
 */
object ApkServer {
    const val PORT = 8765
    private const val TAG = "EvPaylas"
    @Volatile private var socket: ServerSocket? = null

    @Synchronized
    fun start(context: Context): Boolean {
        if (socket?.isClosed == false) return true
        val apk = File(context.applicationInfo.sourceDir)
        val version = Updater.currentVersion(context)
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sunucu açılamadı", e)
            return false
        }
        socket = s
        thread(isDaemon = true, name = "apk-paylas") {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) {
                    try {
                        client.use { c ->
                            c.soTimeout = 10_000
                            val path = c.getInputStream().bufferedReader().readLine()
                                ?.split(" ")?.getOrNull(1).orEmpty()
                            val out = c.getOutputStream()
                            if (path == "/version") {
                                val body = version.toString().toByteArray()
                                out.write(header("text/plain", body.size.toLong(), null))
                                out.write(body)
                            } else {
                                out.write(header("application/vnd.android.package-archive", apk.length(), "ev-iletisimi.apk"))
                                apk.inputStream().use { it.copyTo(out) }
                            }
                            out.flush()
                        }
                    } catch (e: IOException) {
                        Log.i(TAG, "İndirme yarıda kaldı: ${e.message}")
                    }
                }
            }
        }
        return true
    }

    private fun header(type: String, length: Long, fileName: String?) = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: $type\r\n")
        if (fileName != null) append("Content-Disposition: attachment; filename=\"$fileName\"\r\n")
        append("Content-Length: $length\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray()
}
