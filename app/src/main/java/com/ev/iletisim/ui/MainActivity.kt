package com.ev.iletisim.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.google.zxing.integration.android.IntentIntegrator

/** Kurulmamışsa aile kurma / katılma ekranı, kurulduysa aile cihazları listesi. */
class MainActivity : Activity() {
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Ev.addListener(refresh)
        render()
    }

    override fun onPause() {
        Ev.removeListener(refresh)
        super.onPause()
    }

    private fun render() {
        val me = Ev.identity
        if (me == null) renderSetup() else {
            EvService.start(this)
            renderFamily(me.name, me.role)
        }
    }

    private fun renderSetup() {
        val root = column()
        root.addView(title("Ev İletişimi"))
        root.addView(text("Bu cihaz henüz bir aileye bağlı değil."))
        root.addView(button("Yeni aile kur (bu telefon yetişkinin)") { askNameAndCreate() })
        root.addView(button("Aileye katıl (QR okut)") { scanInvite() })
        fitSystemBars(root)
        setContentView(root)
    }

    private fun renderFamily(myName: String, role: Role) {
        val root = column()
        root.addView(title("Merhaba, $myName"))
        if (role == Role.ADMIN) {
            root.addView(button("Yeni cihaz ekle") { startActivity(Intent(this, PairActivity::class.java)) })
        }
        val contacts = Ev.db.contacts()
        if (contacts.isEmpty()) {
            root.addView(text("Henüz başka cihaz görülmedi. Diğer cihazlar aynı Wi‑Fi'da açık olmalı."))
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, contacts.map(::label))
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, contacts[pos].deviceId))
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(button("Pil kısıtlamasını kaldır") { askBatteryExemption() }.apply {
            val pm = getSystemService(PowerManager::class.java)!!
            if (pm.isIgnoringBatteryOptimizations(packageName)) visibility = android.view.View.GONE
        })
        fitSystemBars(root)
        setContentView(root)
    }

    private fun label(p: PeerInfo): String {
        val online = if (Ev.peers?.isOnline(p.deviceId) == true) "● açık" else "○ kapalı"
        return "${p.name}  (${p.role.label})   $online"
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
    private fun askBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}

internal fun Activity.column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = (16 * resources.displayMetrics.density).toInt()
    setPadding(pad, pad, pad, pad)
}

internal fun Activity.title(s: String) = TextView(this).apply {
    text = s
    textSize = 24f
}

internal fun Activity.text(s: String) = TextView(this).apply {
    text = s
    textSize = 16f
}

internal fun Activity.button(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    setOnClickListener { onClick() }
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
