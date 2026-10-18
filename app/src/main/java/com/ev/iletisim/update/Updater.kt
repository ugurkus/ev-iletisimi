package com.ev.iletisim.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ev.iletisim.Ev
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Yeni sürüm arar: önce internette (GitHub'daki son derleme), sonra evde bağlı
 * aile cihazlarında. Hangisi daha yeniyse onu indirir ve kurulum ekranını açar.
 * Kurulumu Android yapar; imza eskisiyle aynı değilse kabul etmez.
 */
object Updater {
    private const val ONLINE_BASE = "https://raw.githubusercontent.com/ugurkus/ev-iletisimi/apk/"

    class Update(val version: Long, val url: String, val fromHome: Boolean)

    fun currentVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** Ağ işidir; ana iş parçacığında çağrılmaz. Yeni sürüm yoksa null. */
    fun findUpdate(context: Context): Update? {
        val candidates = mutableListOf<Update>()
        readVersion(ONLINE_BASE + "version.txt", 8_000)?.let {
            candidates += Update(it, ONLINE_BASE + "ev-iletisimi.apk", fromHome = false)
        }
        // Yalnızca el sıkışmayla doğrulanmış aile cihazlarına sorulur
        Ev.peers?.onlineHosts().orEmpty().forEach { host ->
            val base = "http://${host.hostAddress}:${ApkServer.PORT}/"
            readVersion(base + "version", 3_000)?.let {
                candidates += Update(it, base + "ev-iletisimi.apk", fromHome = true)
            }
        }
        val mine = currentVersion(context)
        // Aynı sürüm hem evde hem internette varsa evdeki daha hızlı iner
        return candidates.filter { it.version > mine }
            .maxWithOrNull(compareBy<Update> { it.version }.thenBy { it.fromHome })
    }

    /** Ağ işidir. İndirilen dosyayı döner; başarısızsa null. */
    fun download(context: Context, update: Update): File? {
        val dir = File(context.cacheDir, "paylas").apply { mkdirs() }
        val out = File(dir, "guncelleme.apk")
        return try {
            val c = URL(update.url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            c.inputStream.buffered(256 * 1024).use { input -> out.outputStream().buffered(256 * 1024).use { input.copyTo(it, 256 * 1024) } }
            out
        } catch (e: IOException) {
            out.delete()
            null
        }
    }

    /**
     * Kurulum ekranını açar. Uygulamanın ilk kez kurulum yapması için Android bir
     * kerelik izin ister; izin yoksa o ayar açılır ve false döner.
     */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.paylas", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }

    private fun readVersion(url: String, timeoutMs: Int): Long? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.inputStream.bufferedReader().use { it.readText().trim().toLongOrNull() }
    } catch (e: IOException) {
        null
    }
}
