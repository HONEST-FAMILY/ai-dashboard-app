package com.honestfamily.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ScheduleFactory(applicationContext)
}

private data class ScheduleItem(
    val title: String,
    val dateLabel: String,
    val meta: String,
    val color: Int,
    val done: Boolean
)

private class ScheduleFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private val items = ArrayList<ScheduleItem>()

    private val palette = intArrayOf(
        Color.parseColor("#9CA3AF"),
        Color.parseColor("#EF4444"),
        Color.parseColor("#F97316"),
        Color.parseColor("#EAB308"),
        Color.parseColor("#22C55E"),
        Color.parseColor("#06B6D4"),
        Color.parseColor("#3B82F6"),
        Color.parseColor("#8B5CF6"),
        Color.parseColor("#EC4899")
    )

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items.clear()
        val token = AppConfig.savedToken(context) ?: return
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 30)
        val to = fmt.format(cal.time)

        val body = Api.get("/schedules?from=$today&to=$to", token) ?: return
        try {
            val arr = JSONObject(body).optJSONArray("data") ?: return
            val todayCal = Calendar.getInstance()
            var i = 0
            while (i < arr.length() && items.size < 50) {
                val o = arr.getJSONObject(i)
                val project = o.optString("project_name", "")
                items.add(
                    ScheduleItem(
                        title = o.optString("title", "(제목 없음)"),
                        dateLabel = friendlyDate(o.optString("scheduled_date", ""), fmt, todayCal),
                        meta = if (project.isNotBlank() && project != "null") project else "",
                        color = palette.getOrElse(o.optInt("color", 0)) { palette[0] },
                        done = o.optBoolean("is_done", false)
                    )
                )
                i++
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    private fun friendlyDate(date: String, fmt: SimpleDateFormat, todayCal: Calendar): String {
        if (date.isBlank()) return ""
        return try {
            val d: Date = fmt.parse(date) ?: return date
            val c = Calendar.getInstance().apply { time = d }
            when (daysBetween(todayCal, c)) {
                0L -> "오늘"
                1L -> "내일"
                else -> SimpleDateFormat("M.d(E)", Locale.KOREA).format(d)
            }
        } catch (e: Exception) {
            date
        }
    }

    private fun daysBetween(a: Calendar, b: Calendar): Long {
        val a0 = midnight(a)
        val b0 = midnight(b)
        return (b0 - a0) / (24L * 3600L * 1000L)
    }

    private fun midnight(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
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
        val v = RemoteViews(context.packageName, R.layout.widget_schedule_item)
        v.setTextViewText(R.id.item_date, item.dateLabel)
        v.setInt(R.id.item_dot, "setColorFilter", item.color)
        if (item.done) {
            v.setTextViewText(R.id.item_title, "✓ " + item.title)
            v.setTextColor(R.id.item_title, Color.parseColor("#9CA3AF"))
        } else {
            v.setTextViewText(R.id.item_title, item.title)
        }
        if (item.meta.isBlank()) {
            v.setViewVisibility(R.id.item_meta, View.GONE)
        } else {
            v.setViewVisibility(R.id.item_meta, View.VISIBLE)
            v.setTextViewText(R.id.item_meta, item.meta)
        }
        v.setOnClickFillInIntent(
            R.id.item_root,
            Intent().putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/schedule")
        )
        return v
    }
}
