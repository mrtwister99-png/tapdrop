package cz.tapdrop.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cz.tapdrop.app.MainActivity
import cz.tapdrop.app.R

/** Widget na ploše: klepnutí otevře obrazovku "Odeslat". */
class QuickSendWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("route", "send")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 100, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_send)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
