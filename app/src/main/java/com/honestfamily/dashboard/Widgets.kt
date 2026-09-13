package com.honestfamily.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object Widgets {

    fun piFlags(mutable: Boolean): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (mutable) {
            if (Build.VERSION.SDK_INT >= 31) base or PendingIntent.FLAG_MUTABLE else base
        } else {
            base or PendingIntent.FLAG_IMMUTABLE
        }
    }

    fun refreshAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        notify(context, mgr, ScheduleWidgetProvider::class.java)
        notify(context, mgr, FavoritesWidgetProvider::class.java)
    }

    private fun notify(context: Context, mgr: AppWidgetManager, cls: Class<*>) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
        if (ids.isEmpty()) return
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        context.sendBroadcast(
            Intent(context, cls)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }
}
