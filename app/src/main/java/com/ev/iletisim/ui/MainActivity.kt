package com.ev.iletisim.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.ev.iletisim.Ev
import com.ev.iletisim.EvService
import com.ev.iletisim.core.PairingInvite
import com.ev.iletisim.core.PeerInfo
import com.ev.iletisim.core.Role
import com.ev.iletisim.update.Updater
import com.google.zxing.integration.android.IntentIntegrator

/** Kurulmamışsa aile kurma / katılma ekranı, kurulduysa aile cihazları listesi. */
class MainActivity : Activity() {
    private lateinit var list: ListView
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // Bağlanma durumu her an değişebilir; ekran açıkken birkaç saniyede bir yenilenir
    private val ticker = object : Runnable {
        override fun run() {
            if (Ev.identity != null) render()
            handler.postDelayed(this, 3_000)
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        Ev.addListener(refresh)
        render()
        handler.postDelayed(ticker, 3_000)
    }

    override fun onPause() {
        Ev.removeListener(refresh)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val me = Ev.identity
        if (me == null) renderSetup() else {
            EvService.start(this)
            renderFamily(me.name, me.role)
            if (!autoChecked) {
                autoChecked = true
                // Servis aile cihazlarına bağlanırken biraz beklenir
                list.postDelayed({ checkUpdate(quiet = true) }, 5_000)
            }
        }
    }

    private fun checkUpdate(quiet: Boolean) {
        if (!quiet) Toast.makeText(this, "Güncelleme aranıyor…", Toast.LENGTH_SHORT).show()
        Ev.io.execute {
            val update = Updater.findUpdate(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (update == null) {
                    if (!quiet) Toast.makeText(this, "Uygulama güncel", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Yeni sürüm var")
                    .setMessage(if (update.fromHome) "Evdeki bir cihazdan indirilecek." else "İnternetten indirilecek.")
                    .setPositiveButton("Güncelle") { _, _ -> downloadAndInstall(update) }
                    .setNegativeButton("Sonra", null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(update: Updater.Update) {
        Toast.makeText(this, "İndiriliyor, biraz sürebilir…", Toast.LENGTH_LONG).show()
        Ev.io.execute {
            val apk = Updater.download(this, update)
            runOnUiThread {
                when {
                    apk == null -> Toast.makeText(this, "İndirilemedi, sonra tekrar deneyin", Toast.LENGTH_LONG).show()
                    !Updater.install(this, apk) ->
                        Toast.makeText(this, "İzni açıp geri dönün, sonra yine Güncelle'ye basın", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderSetup() {
        val root = column()
        root.addView(title("Ev İletişimi"))
        root.addView(text("Bu cihaz henüz bir aileye bağlı değil."))
        root.addView(button("Yeni aile kur (bu telefon yetişkinin)") { askNameAndCreate() })
        root.addView(button("Aileye katıl (QR okut)") { scanInvite() })
        root.addView(button("📲 Uygulamayı paylaş", Look.TONAL) { startActivity(Intent(this, ShareActivity::class.java)) })
        fitSystemBars(root)
        setContentView(root)
    }

    private fun renderFamily(myName: String, role: Role) {
        val root = column()
        root.addView(title("Merhaba, $myName"))
        if (role == Role.ADMIN) {
            root.addView(button("➕ Yeni cihaz ekle (QR göster)") { startActivity(Intent(this, PairActivity::class.java)) })
        }
        val tools = LinearLayout(this)
        tools.addView(
            button("📲 Paylaş", Look.TONAL) { startActivity(Intent(this, ShareActivity::class.java)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) },
        )
        tools.addView(
            button("🔄 Güncelle", Look.TONAL) { checkUpdate(quiet = false) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(tools)

        root.addView(section("AİLEM"))
        val contacts = Ev.db.contacts()
        networkNote(contacts.isEmpty())?.let { root.addView(card(text(it))) }
        list = ListView(this).apply {
            divider = null
            dividerHeight = dp(12)
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        list.adapter = object : ArrayAdapter<PeerInfo>(this, 0, contacts) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                contactCard(contacts[position])
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, contacts[pos].deviceId))
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        val pm = getSystemService(PowerManager::class.java)!!
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            root.addView(button("🔋 Pil kısıtlamasını kaldır", Look.TONAL) { askBatteryExemption() })
        }
        fitSystemBars(root)
        setContentView(root)
    }

    /** Kişi listesinin üstündeki not: ağ sorunu, arama ya da bağlanma durumu. */
    private fun networkNote(noContacts: Boolean): String? {
        Ev.netProblem?.let { return it }
        val peers = Ev.peers ?: return "Başlatılıyor…"
        val connected = peers.onlineHosts().size
        val known = peers.knownCount()
        return when {
            known > connected -> buildString {
                append("Evde ${known - connected} cihaz bulundu, bağlanılıyor…")
                peers.lastError?.let { append("\n\nSon hata: ").append(it) }
            }
            noContacts -> "Evdeki diğer cihazlar aranıyor… Hepsi aynı Wi‑Fi'a bağlı ve uygulama açık olmalı."
            else -> null
        }
    }

    private fun contactCard(p: PeerInfo): View {
        val pal = Palette(this)
        val peers = Ev.peers
        val (state, color) = when {
            peers?.isOnline(p.deviceId) == true -> "● Bağlı" to pal.online
            peers?.isKnown(p.deviceId) == true -> "◌ Bağlanıyor…" to pal.subtle
            else -> "○ Görünmüyor" to pal.subtle
        }
        val name = TextView(this).apply {
            text = p.name
            textSize = 20f
            setTextColor(pal.text)
        }
        val info = TextView(this).apply {
            text = "${p.role.label}  ·  $state"
            textSize = 15f
            setTextColor(color)
        }
        return card().apply {
            minimumHeight = dp(72)
            addView(name)
            addView(info)
        }
    }

    private fun askNameAndCreate() {
        val input = EditText(this).apply { hint = "Örn. Babam" }
        AlertDialog.Builder(this)
            .setTitle("Bu telefonun adı")
            .setView(input)
            .setPositiveButton("Kur") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Ev.identities.createFamily(name)
                render()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun scanInvite() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Yetişkin telefondaki QR'ı okutun")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Activity sonuç API'si; AndroidX kullanmamak için tercih edildi")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val contents = result.contents ?: return
        val invite = PairingInvite.decode(contents)
        if (invite == null) {
            Toast.makeText(this, "Bu QR Ev İletişimi'ne ait değil", Toast.LENGTH_LONG).show()
            return
        }
        Ev.identities.join(invite)
        Toast.makeText(this, "Aileye katıldınız: ${invite.name}", Toast.LENGTH_LONG).show()
        render()
    }

    @Suppress("BatteryLife")
    companion object {
        /** Uygulama her açılışında bir kez kendiliğinden güncelleme arar. */
        private var autoChecked = false
    }

    private fun askBatteryExemption() {
        // Doğrudan izin istemek Play Protect'in dikkatini çekiyor; pil ayar listesi açılır
        Toast.makeText(this, "Listede Ev İletişimi'ni bulup \"Kısıtlama yok\" seçin", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

// Android 15'ten itibaren uygulama ekranın tamamına çiziliyor; durum çubuğu,
// alt çubuk ve klavye kadar boşluk bırak ki yazılar altlarında kalmasın
internal fun fitSystemBars(root: View) {
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                left + insets.systemWindowInsetLeft,
                top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight,
                bottom + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
}
