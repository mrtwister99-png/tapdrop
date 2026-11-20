package cz.tapdrop.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import cz.tapdrop.app.data.HistoryStore
import cz.tapdrop.app.data.Prefs

class TapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        history = HistoryStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Průběh přenosu", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Výsledek přenosu", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_RESULT = "result"
        lateinit var instance: TapApp
            private set
        lateinit var prefs: Prefs
            private set
        lateinit var history: HistoryStore
            private set
    }
}
