package com.ev.iletisim

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.ev.iletisim.core.CallManager
import com.ev.iletisim.core.Identity
import com.ev.iletisim.data.Db
import com.ev.iletisim.data.IdentityStore
import com.ev.iletisim.net.PeerManager
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Uygulama genelinde paylaşılan durum: veritabanı, kimlik ve çalışan eş yöneticisi. */
class EvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ev.init(this)
        // Beklenmedik çökme olursa kısa özeti saklanır; ana ekran bir sonraki açılışta gösterir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val trace = e.stackTraceToString().lineSequence().take(12).joinToString("\n")
            getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", trace).commit()
            previous?.uncaughtException(thread, e)
        }
    }
}

object Ev {
    lateinit var db: Db
        private set
    lateinit var identities: IdentityStore
        private set

    /** Servis çalışırken dolu olur. */
    @Volatile var peers: PeerManager? = null
    @Volatile var calls: CallManager? = null
    /** Servisin ağ tarafında ciddi bir sorun varsa ekranda gösterilir. */
    @Volatile var netProblem: String? = null

    /** Ağ işleri ana iş parçacığında yapılamaz; ekranlar mesaj göndermek için bunu kullanır. */
    val io = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun init(app: Application) {
        db = Db(app)
        identities = IdentityStore(app)
    }

    val identity: Identity? get() = identities.load()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Mesaj geldi, iletildi ya da biri bağlandı: açık ekranlar kendini yeniler. */
    fun notifyChanged() = main.post { listeners.forEach { it() } }
}
