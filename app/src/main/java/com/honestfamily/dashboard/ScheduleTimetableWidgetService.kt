package com.honestfamily.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleTimetableWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TimetableFactory(applicationContext, intent)
}

private const val KIND_HOUR = 0
private const val KIND_EVENT = 1
private const val KIND_ALLDAY = 2

private data class TtRow(
    val kind: Int,
    val hourLabel: String,
    val timeLabel: String,
    val title: String,
    val color: Int,
    val isNow: Boolean,
    val openUrl: String
)

private data class TtEvent(
    val id: Int,
    val title: String,
    val color: Int,
    val start: Int?,
    val end: Int?,
    val position: Int
)

private class TimetableFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private val rows = ArrayList<TtRow>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows.clear()
        val prefs = context.getSharedPreferences("hf_dashboard", Context.MODE_PRIVATE)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val todayStr = fmt.format(Calendar.getInstance().time)
        val date = prefs.getString("ttdate_$widgetId", todayStr) ?: todayStr
        val token = AppConfig.widgetToken(context) ?: return

        val body = loadBody(prefs, token, date)
        val events = parse(body, date)

        val allday = ArrayList<TtEvent>()
        val timed = ArrayList<TtEvent>()
        for (e in events) {
            if (e.start != null && e.end != null && e.end > e.start) timed.add(e) else allday.add(e)
        }
        allday.sortWith(compareBy({ it.position }, { it.id }))
        timed.sortWith(compareBy({ it.start ?: 0 }, { it.id }))

        for (e in allday) {
            rows.add(TtRow(KIND_ALLDAY, "종일", "", e.title, e.color, false, focusUrl(e.id)))
        }

        val isToday = date == todayStr
        val now = Calendar.getInstance()
        val nowHour = now.get(Calendar.HOUR_OF_DAY)
        val nowMin = nowHour * 60 + now.get(Calendar.MINUTE)

        for (h in 0..23) {
            val isNow = isToday && h == nowHour
            rows.add(
                TtRow(
                    KIND_HOUR,
                    "${h}시",
                    if (isNow) "지금 " + hhmm(nowMin) else "",
                    "",
                    0,
                    isNow,
                    dayUrl(date)
                )
            )
            for (e in timed) {
                val startH = (e.start ?: 0) / 60
                if (startH == h) {
                    rows.add(
                        TtRow(
                            KIND_EVENT,
                            "",
                            hhmm(e.start!!) + " – " + hhmm(e.end!!),
                            e.title,
                            e.color,
                            false,
                            focusUrl(e.id)
                        )
                    )
                }
            }
        }
    }

    private fun loadBody(prefs: android.content.SharedPreferences, token: String, date: String): String? {
        val res = Api.request("/schedules?from=$date&to=$date&mine=1&is_done=0", token)
        if (res.code == 200 && res.body != null) {
            prefs.edit().putString("tt_range_$widgetId", date).putString("tt_body_$widgetId", res.body).apply()
            return res.body
        }
        if (prefs.getString("tt_range_$widgetId", null) == date) {
            return prefs.getString("tt_body_$widgetId", null)
        }
        return null
    }

    private fun parse(body: String?, date: String): List<TtEvent> {
        if (body == null) return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val list = ArrayList<TtEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("scheduled_date", "") != date) continue
                if (o.optBoolean("is_done", false)) continue
                list.add(
                    TtEvent(
                        id = o.optInt("id", 0),
                        title = o.optString("title", "(제목 없음)"),
                        color = o.optInt("color", 1),
                        start = if (o.isNull("start_minute")) null else o.optInt("start_minute"),
                        end = if (o.isNull("end_minute")) null else o.optInt("end_minute"),
                        position = o.optInt("position", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {
        rows.clear()
    }

    override fun getCount(): Int = rows.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows[position]
        val v = RemoteViews(context.packageName, R.layout.widget_tt_item)

        val muted = ContextCompat.getColor(context, R.color.widget_text_muted)
        val text = ContextCompat.getColor(context, R.color.widget_text)
        val rose = ContextCompat.getColor(context, R.color.widget_rose)

        when (row.kind) {
            KIND_HOUR -> {
                v.setTextViewText(R.id.tt_hour, row.hourLabel)
                v.setViewVisibility(R.id.tt_bar, View.GONE)
                v.setViewVisibility(R.id.tt_title, View.GONE)
                if (row.isNow) {
                    v.setViewVisibility(R.id.tt_nowline, View.VISIBLE)
                    v.setTextColor(R.id.tt_hour, rose)
                    v.setTextViewText(R.id.tt_time, row.timeLabel)
                    v.setTextColor(R.id.tt_time, rose)
                    v.setViewVisibility(R.id.tt_time, View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.tt_nowline, View.GONE)
                    v.setTextColor(R.id.tt_hour, muted)
                    v.setViewVisibility(R.id.tt_time, View.GONE)
                }
            }
            else -> {
                v.setViewVisibility(R.id.tt_nowline, View.GONE)
                v.setTextViewText(R.id.tt_hour, row.hourLabel)
                v.setTextColor(R.id.tt_hour, muted)
                v.setViewVisibility(R.id.tt_bar, View.VISIBLE)
                v.setInt(R.id.tt_bar, "setBackgroundColor", barColor(row.color))
                v.setViewVisibility(R.id.tt_title, View.VISIBLE)
                v.setTextViewText(R.id.tt_title, row.title)
                v.setTextColor(R.id.tt_title, text)
                if (row.timeLabel.isNotEmpty()) {
                    v.setViewVisibility(R.id.tt_time, View.VISIBLE)
                    v.setTextViewText(R.id.tt_time, row.timeLabel)
                    v.setTextColor(R.id.tt_time, muted)
                } else {
                    v.setViewVisibility(R.id.tt_time, View.GONE)
                }
            }
        }

        v.setOnClickFillInIntent(
            R.id.tt_root,
            Intent().putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + row.openUrl)
        )
        return v
    }

    private fun focusUrl(id: Int) = "/schedule?focus=$id"
    private fun dayUrl(date: String) = "/schedule?date=$date"
    private fun hhmm(m: Int): String = String.format(Locale.KOREA, "%02d:%02d", m / 60, m % 60)

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
