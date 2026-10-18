package com.ev.iletisim.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Uygulamanın görünümü tek yerde: renkler (Android 12+ cihazlarda duvar kağıdından
 * gelen dinamik renkler), boşluklar, yuvarlatılmış düğmeler ve kartlar.
 * Çocuklar da rahat basabilsin diye düğmeler büyük, yazılar iri.
 */
internal class Palette(context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private fun sys(id: Int, fallback: Int) =
        if (Build.VERSION.SDK_INT >= 31) context.getColor(id) else fallback

    val primary = if (dark) sys(android.R.color.system_accent1_200, 0xFF8FD3C3.toInt())
        else sys(android.R.color.system_accent1_600, 0xFF00796B.toInt())
    val onPrimary = if (dark) sys(android.R.color.system_accent1_800, 0xFF00382F.toInt()) else Color.WHITE
    val tonal = if (dark) sys(android.R.color.system_accent2_700, 0xFF33504A.toInt())
        else sys(android.R.color.system_accent2_100, 0xFFCDE8E1.toInt())
    val onTonal = if (dark) sys(android.R.color.system_accent2_100, 0xFFCDE8E1.toInt())
        else sys(android.R.color.system_accent2_900, 0xFF06201B.toInt())
    val background = if (dark) sys(android.R.color.system_neutral1_900, 0xFF121614.toInt())
        else sys(android.R.color.system_neutral1_10, 0xFFF6FBF8.toInt())
    val surface = if (dark) sys(android.R.color.system_neutral1_800, 0xFF1E2421.toInt())
        else sys(android.R.color.system_neutral1_50, 0xFFE9F0EC.toInt())
    val text = if (dark) sys(android.R.color.system_neutral1_50, 0xFFE6EBE8.toInt())
        else sys(android.R.color.system_neutral1_900, 0xFF171D1B.toInt())
    val subtle = if (dark) sys(android.R.color.system_neutral2_200, 0xFFBFC9C4.toInt())
        else sys(android.R.color.system_neutral2_700, 0xFF3F4945.toInt())
    val danger = if (dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val onDanger = if (dark) 0xFF690005.toInt() else Color.WHITE
    val online = if (dark) 0xFF7DDB9A.toInt() else 0xFF1B7F3B.toInt()
}

enum class Look { FILLED, TONAL, DANGER }

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Context.rounded(color: Int, radiusDp: Int = 20) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

/** Ekran gövdesi: 16dp kenar, öğeler arasında 12dp boşluk. */
internal class Column(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val pad = context.dp(16)
        setPadding(pad, pad, pad, pad)
        setBackgroundColor(Palette(context).background)
    }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) }
}

internal fun Activity.column() = Column(this)

internal fun Activity.title(s: String) = TextView(this).apply {
    text = s
    textSize = 28f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette(context).text)
    setPadding(0, dp(8), 0, dp(4))
}

internal fun Activity.text(s: String) = TextView(this).apply {
    text = s
    textSize = 17f
    setLineSpacing(0f, 1.15f)
    setTextColor(Palette(context).subtle)
}

/** Bölüm başlığı, örn. "Ailem". */
internal fun Activity.section(s: String) = TextView(this).apply {
    text = s
    textSize = 15f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.05f
    setTextColor(Palette(context).primary)
    setPadding(0, dp(12), 0, 0)
}

internal fun Activity.button(label: String, look: Look = Look.FILLED, onClick: () -> Unit) = Button(this).apply {
    val p = Palette(context)
    val (bg, fg) = when (look) {
        Look.FILLED -> p.primary to p.onPrimary
        Look.TONAL -> p.tonal to p.onTonal
        Look.DANGER -> p.danger to p.onDanger
    }
    text = label
    isAllCaps = false
    textSize = 17f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(fg)
    minHeight = dp(56)
    minimumHeight = dp(56)
    stateListAnimator = null
    elevation = 0f
    setPadding(dp(20), dp(12), dp(20), dp(12))
    background = RippleDrawable(ColorStateList.valueOf((fg and 0x00FFFFFF) or 0x33000000), context.rounded(bg, 28), null)
    setOnClickListener { onClick() }
}

/** Yuvarlatılmış, hafif renkli kutu; kişi listesi ve bilgi notları için. */
internal fun Activity.card(content: View? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
    background = rounded(Palette(context).surface, 20)
    val pad = dp(16)
    setPadding(pad, pad, pad, pad)
    content?.let { addView(it) }
}

/** Bu cihazın ev Wi‑Fi'ındaki adresi (bağlı değilse null). */
internal fun wifiAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
} catch (e: java.net.SocketException) {
    null
}
