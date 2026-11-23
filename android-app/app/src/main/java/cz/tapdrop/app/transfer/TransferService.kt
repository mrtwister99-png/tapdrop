package cz.tapdrop.app.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.tapdrop.app.TapApp
import cz.tapdrop.app.net.Discovery
import cz.tapdrop.app.net.ErrorKind
import cz.tapdrop.app.net.TapError
import cz.tapdrop.app.net.czFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground služba, která provede celý přenos po přiložení tagu.
 * Běží i když uživatel odejde z aplikace; po dokončení pošle notifikaci.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var busy = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notifier.PROGRESS_ID, Notifier.progress(this, null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (busy) return START_NOT_STICKY
        busy = true
        val action = intent?.action ?: ACTION_DOWNLOAD
        acquireLocks()

        scope.launch {
            try {
                run(action)
            } finally {
                TransferBus.idle()
                releaseLocks()
                busy = false
                ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        scope.launch {
            var last = 0L
            TransferBus.ui.collect { u ->
                val now = System.currentTimeMillis()
                if (u.active && now - last > 700) {
                    last = now
                    runCatching {
                        NotificationManagerCompat.from(this@TransferService)
                            .notify(Notifier.PROGRESS_ID, Notifier.progress(this@TransferService, u))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(action: String) {
        val prefs = TapApp.prefs
        val ctx = applicationContext
        val transfer = Transfer(ctx, prefs, TapApp.history)
        TransferBus.setResult(null)
        TransferBus.update(TransferUi(active = true, phase = "Hledám notebook…"))
        try {
            val api = Discovery.connect(ctx, prefs)

            var sent = 0
            var received = 0
            var waiting = 0

            val wantUpload = when (action) {
                ACTION_UPLOAD -> prefs.queue.isNotEmpty()
                else -> false
            }
            if (wantUpload) sent = transfer.uploadQueue(api)

            val wantDownload = when (action) {
                ACTION_DOWNLOAD -> true
                else -> false
            }
            if (wantDownload) {
                // TADY NENÍ ŽÁDNÝ DIALOG: stáhne se všechno, co čeká na PC
                received = transfer.downloadAll(api)
            }

            val parts = mutableListOf<String>()
            if (sent > 0) parts += "Odesláno: ${czFiles(sent)}"
            if (received > 0) parts += "Staženo: ${czFiles(received)}"
            val title: String
            val detail: String
            if (parts.isNotEmpty()) {
                title = parts.joinToString(" · ")
                detail = if (received > 0 && !prefs.downloadTree.isNotBlank()) "Uloženo do Downloads/TapDrop" else ""
            } else if (waiting > 0) {
                title = "Na notebooku čeká ${czFiles(waiting)}"
                detail = "Automatické stahování je vypnuté – stáhni je v záložce Přijato."
            } else {
                title = "Není co přenášet"
                detail = "Nic není ve frontě a na notebooku nic nečeká."
            }
            val ok = true
            TransferBus.setResult(TransferResult(ok, title, detail))
            if (sent > 0 || received > 0) {
                Haptics.success(ctx)
                Notifier.playSuccessSound(ctx)
                Notifier.result(ctx, if (sent > 0 && received == 0) "Odesláno" else title, if (sent > 0 && received == 0) czFiles(sent) else detail, true)
            } else if (waiting > 0) {
                Notifier.result(ctx, title, detail, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TapError) {
            Log.w(TAG, "Chyba: ${e.kind} ${e.message}")
            if (action == ACTION_UPLOAD && prefs.queue.isNotEmpty() &&
                (e.kind == ErrorKind.WIFI_OFF || e.kind == ErrorKind.NOT_FOUND)
            ) {
                // offline: soubory zůstávají ve frontě a odešlou se samy po připojení
                prefs.sendWhenOnline = true
                fail("Čeká na připojení", "Soubory se odešlou automaticky, jakmile bude notebook dostupný.")
                return
            }
            fail(e.title, if (e.message != null && e.message != e.title) "${e.message}" else TapError.hint(e.kind))
        } catch (e: Exception) {
            Log.e(TAG, "Neočekávaná chyba", e)
            fail("Přenos se nezdařil", e.message ?: "Neznámá chyba")
        }
    }

    private fun fail(title: String, detail: String) {
        TransferBus.setResult(TransferResult(false, title, detail))
        Haptics.error(this)
        Notifier.result(this, title, detail, false)
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapDrop:transfer").apply { acquire(30 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapDrop:wifi").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TapDrop.Service"
        const val ACTION_UPLOAD = "cz.tapdrop.app.UPLOAD"
        const val ACTION_DOWNLOAD = "cz.tapdrop.app.DOWNLOAD"

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, TransferService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
