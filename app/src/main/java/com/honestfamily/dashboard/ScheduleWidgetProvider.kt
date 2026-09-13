package com.honestfamily.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            for (id in ids) updateWidget(context, mgr, id)
        }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)

        val svc = Intent(context, ScheduleWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        val loggedIn = AppConfig.savedToken(context) != null
        views.setTextViewText(
            R.id.widget_empty,
            context.getString(if (loggedIn) R.string.schedule_empty else R.string.need_login)
        )

        val openSchedule = Intent(context, MainActivity::class.java).apply {
            putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/schedule")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.widget_title,
            PendingIntent.getActivity(context, 101, openSchedule, Widgets.piFlags(false))
        )

        val template = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, 102, template, Widgets.piFlags(true))
        )

        val refresh = Intent(context, ScheduleWidgetProvider::class.java).setAction(ACTION_REFRESH)
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(context, 103, refresh, Widgets.piFlags(false))
        )

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    companion object {
        const val ACTION_REFRESH = "com.honestfamily.dashboard.ACTION_SCHEDULE_REFRESH"
    }
}
