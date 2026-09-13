package com.honestfamily.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class FavoritesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FavoritesWidgetProvider::class.java))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            for (id in ids) updateWidget(context, mgr, id)
        }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_favorites)

        val svc = Intent(context, FavoritesWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        val loggedIn = AppConfig.savedToken(context) != null
        views.setTextViewText(
            R.id.widget_empty,
            context.getString(if (loggedIn) R.string.favorites_empty else R.string.need_login)
        )

        val openHome = Intent(context, MainActivity::class.java).apply {
            putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.widget_title,
            PendingIntent.getActivity(context, 201, openHome, Widgets.piFlags(false))
        )

        val template = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(context, 202, template, Widgets.piFlags(true))
        )

        val refresh = Intent(context, FavoritesWidgetProvider::class.java).setAction(ACTION_REFRESH)
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(context, 203, refresh, Widgets.piFlags(false))
        )

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    companion object {
        const val ACTION_REFRESH = "com.honestfamily.dashboard.ACTION_FAVORITES_REFRESH"
    }
}
