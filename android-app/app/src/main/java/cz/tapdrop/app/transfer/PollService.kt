package cz.tapdrop.app.transfer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.tapdrop.app.TapApp
import cz.tapdrop.app.net.Api
import cz.tapdrop.app.net.Discovery
import cz.tapdrop.app.net.Endpoint
import cz.tapdrop.app.net.TapError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Stav spojení s notebookem (null = zatím neznámo / služba neběží). */
object PollBus {
    val connected = MutableStateFlow<Boolean?>(null)
}

/**
 * Po spárování běží na pozadí a každých pár sekund se ptá notebooku na /pending.
 * Když tam jsou soubory (a je zapnuté automatické stahování), rovnou je stáhne – bez potvrzování.
 */
class PollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, TapApp.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("TapDrop je připojen")
            .setContentText("Soubory z notebooku se stahují automaticky")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private suspend fun loop() {
        val prefs = TapApp.prefs
        var failures = 0
        while (scope.isActive) {
            if (!prefs.paired) { PollBus.connected.value = null; delay(3000); continue }
            try {
                val api = Api(Endpoint(prefs.host, prefs.port), prefs.token)
                val pending = api.pending()
                failures = 0
                PollBus.connected.value = true
                if (pending.isNotEmpty() && prefs.autoDownload && !TransferBus.ui.value.active) {
                    TransferService.start(applicationContext, TransferService.ACTION_DOWNLOAD)
                    delay(4000) // dej službě čas se rozjet
                }
            } catch (e: Exception) {
                PollBus.connected.value = false
                failures++
                if (e is TapError) { delay(10_000); continue }
                // IP se mohla změnit – zkus mDNS
                if (failures % 3 == 0) runCatching { Discovery.connect(applicationContext, prefs); failures = 0 }
            }
            delay(if (PollBus.connected.value == true) 3000 else 6000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        PollBus.connected.value = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 3

        fun start(ctx: Context) {
            if (TapApp.prefs.paired && TapApp.prefs.keepAlive)
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PollService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PollService::class.java))
        }
    }
}
