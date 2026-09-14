package com.honestfamily.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import org.json.JSONObject

class ScheduleDayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ScheduleDayFactory(applicationContext, intent)
}

private data class DayItem(val id: Int, val title: String, val color: Int, val done: Boolean)

private class ScheduleDayFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private val items = ArrayList<DayItem>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items.clear()
        val prefs = context.getSharedPreferences("hf_dashboard", Context.MODE_PRIVATE)
        val date = prefs.getString("seldate_$widgetId", null) ?: return
        val token = AppConfig.widgetToken(context) ?: return
        val body = Api.get("/schedules?from=$date&to=$date&mine=1&is_done=0", token) ?: return
        try {
            val arr = JSONObject(body).optJSONArray("data") ?: return
            val rows = ArrayList<Triple<Int, Int, DayItem>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("scheduled_date", "") != date) continue
                if (o.optBoolean("is_done", false)) continue
                val item = DayItem(
                    id = o.optInt("id", 0),
                    title = o.optString("title", "(제목 없음)"),
                    color = o.optInt("color", 1),
                    done = o.optBoolean("is_done", false)
                )
                rows.add(Triple(o.optInt("position", 0), item.id, item))
            }
            rows.sortWith(compareBy({ it.first }, { it.second }))
            for (t in rows) items.add(t.third)
        } catch (e: Exception) {
            items.clear()
        }
    }

    override fun onDestroy() {
        items.clear()
    }

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val v = RemoteViews(context.packageName, R.layout.widget_day_item)
        v.setTextViewText(R.id.day_item_title, item.title)

        val muted = ContextCompat.getColor(context, R.color.widget_text_muted)
        val text = ContextCompat.getColor(context, R.color.widget_text)
        val accent = ContextCompat.getColor(context, R.color.widget_accent)
        v.setTextColor(R.id.day_item_title, if (item.done) muted else text)
        v.setInt(R.id.day_item_bar, "setBackgroundColor", if (item.done) muted else barColor(item.color))
        v.setImageViewResource(
            R.id.day_item_check,
            if (item.done) R.drawable.ic_check_circle else R.drawable.ic_circle
        )
        v.setInt(R.id.day_item_check, "setColorFilter", if (item.done) accent else muted)

        v.setOnClickFillInIntent(
            R.id.day_item_root,
            Intent().putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/schedule?focus=${item.id}")
        )
        return v
    }

    private fun barColor(slot: Int): Int = when (slot) {
        2 -> Color.parseColor("#2563EB")
        3 -> Color.parseColor("#DC2626")
        4 -> Color.parseColor("#F0E442")
        5 -> Color.parseColor("#0072B2")
        6 -> Color.parseColor("#D55E00")
        7 -> Color.parseColor("#CC79A7")
        8 -> Color.parseColor("#7C3AED")
        else -> ContextCompat.getColor(context, R.color.widget_text)
    }
}
