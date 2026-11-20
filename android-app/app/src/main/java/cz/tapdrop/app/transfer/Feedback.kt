package cz.tapdrop.app.transfer

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.tapdrop.app.MainActivity
import cz.tapdrop.app.R
import cz.tapdrop.app.TapApp
import cz.tapdrop.app.net.humanSize

object Haptics {
    private fun vib(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Krátké cuknutí při přiložení tagu. */
    fun tap(ctx: Context) {
        runCatching { vib(ctx)?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun success(ctx: Context) {
        runCatching { vib(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1)) }
    }

    fun error(ctx: Context) {
        runCatching { vib(ctx)?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}

object Notifier {
    const val PROGRESS_ID = 1
    const val RESULT_ID = 2

    private fun open(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(ctx: Context, u: TransferUi?): Notification {
        val b = NotificationCompat.Builder(ctx, TapApp.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
        if (u == null || !u.active) {
            b.setContentTitle("TapDrop").setContentText("Připojuji se k notebooku…").setProgress(0, 0, true)
        } else {
            val pct = (u.fraction * 100).toInt()
            b.setSmallIcon(if (u.upload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            b.setContentTitle("${u.phase}: ${u.fileName}")
            b.setContentText("$pct % · ${humanSize(u.speed)}/s · soubor ${u.fileIndex}/${u.fileCount}")
            b.setProgress(100, pct, false)
        }
        return b.build()
    }

    fun result(ctx: Context, title: String, text: String, ok: Boolean) {
        if (!TapApp.prefs.notifications) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, TapApp.CH_RESULT)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(RESULT_ID, n) }
    }

    fun playSuccessSound(ctx: Context) {
        if (!TapApp.prefs.sound) return
        runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }
}
