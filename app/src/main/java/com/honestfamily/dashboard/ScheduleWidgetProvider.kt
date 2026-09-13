package com.honestfamily.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        renderAsync(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) editor.remove(keyOffset(id))
        editor.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY, ACTION_REFRESH -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val cur = prefs.getInt(keyOffset(id), 0)
                    when (intent.action) {
                        ACTION_PREV -> prefs.edit().putInt(keyOffset(id), cur - 1).apply()
                        ACTION_NEXT -> prefs.edit().putInt(keyOffset(id), cur + 1).apply()
                        ACTION_TODAY -> prefs.edit().putInt(keyOffset(id), 0).apply()
                    }
                    renderAsync(context, intArrayOf(id))
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        EXEC.execute {
            try {
                val mgr = AppWidgetManager.getInstance(appCtx)
                for (id in ids) renderOne(appCtx, mgr, id)
            } catch (e: Exception) {
                // 위젯은 실패해도 조용히 넘어간다 — 다음 새로고침에서 다시 시도한다.
            } finally {
                pending.finish()
            }
        }
    }

    private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val offset = prefs.getInt(keyOffset(id), 0)
        val token = AppConfig.savedToken(context)

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val todayStr = fmt.format(Calendar.getInstance().time)

        val month = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, offset)
        }
        val year = month.get(Calendar.YEAR)
        val mon0 = month.get(Calendar.MONTH)
        views.setTextViewText(R.id.widget_title, "${year}년 ${mon0 + 1}월")

        val sundayFirst = weekStartsOnSunday(context, token)
        val labels = if (sundayFirst)
            arrayOf("일", "월", "화", "수", "목", "금", "토")
        else
            arrayOf("월", "화", "수", "목", "금", "토", "일")
        for (i in 0 until 7) {
            val wid = idOf(context, "wd_$i")
            views.setTextViewText(wid, labels[i])
            views.setTextColor(wid, weekdayColor(context, labels[i]))
        }

        val gridStart = month.clone() as Calendar
        val dowSun0 = gridStart.get(Calendar.DAY_OF_WEEK) - 1
        val back = if (sundayFirst) dowSun0 else (dowSun0 + 6) % 7
        gridStart.add(Calendar.DAY_OF_YEAR, -back)

        val from = fmt.format(gridStart.time)
        val toCal = gridStart.clone() as Calendar
        toCal.add(Calendar.DAY_OF_YEAR, 41)
        val to = fmt.format(toCal.time)

        val byDate: Map<String, List<Ev>>? = if (token == null) null else fetchSchedules(token, from, to)

        val textCol = ContextCompat.getColor(context, R.color.widget_text)
        val mutedCol = ContextCompat.getColor(context, R.color.widget_text_muted)
        val outCol = ContextCompat.getColor(context, R.color.widget_out)
        val roseCol = ContextCompat.getColor(context, R.color.widget_rose)

        val cell = gridStart.clone() as Calendar
        for (i in 0 until 42) {
            val r = i / 7
            val c = i % 7
            val ds = fmt.format(cell.time)
            val inMonth = cell.get(Calendar.MONTH) == mon0 && cell.get(Calendar.YEAR) == year
            val isToday = ds == todayStr
            val isSunday = cell.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

            val dateId = idOf(context, "date_${r}_$c")
            views.setTextViewText(dateId, cell.get(Calendar.DAY_OF_MONTH).toString())
            if (isToday) {
                views.setInt(dateId, "setBackgroundResource", R.drawable.today_circle)
                views.setTextColor(dateId, ContextCompat.getColor(context, R.color.widget_today_text))
            } else {
                views.setInt(dateId, "setBackgroundColor", Color.TRANSPARENT)
                views.setTextColor(dateId, if (!inMonth) outCol else if (isSunday) roseCol else textCol)
            }

            val dayEvents = byDate?.get(ds) ?: emptyList()
            renderCellEvents(context, views, r, c, dayEvents, textCol, mutedCol)
            cell.add(Calendar.DAY_OF_YEAR, 1)
        }

        val needLogin = token == null || byDate == null
        views.setViewVisibility(R.id.widget_empty, if (needLogin) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.need_login))

        bindClicks(context, views, id)
        mgr.updateAppWidget(id, views)
    }

    private fun renderCellEvents(
        context: Context,
        views: RemoteViews,
        r: Int,
        c: Int,
        events: List<Ev>,
        textCol: Int,
        mutedCol: Int
    ) {
        val n = events.size
        for (k in 0 until 3) {
            val evId = idOf(context, "ev_${r}_${c}_$k")
            val overflow = n > 3 && k == 2
            if (k >= n && !overflow) {
                views.setViewVisibility(evId, View.GONE)
                continue
            }
            views.setViewVisibility(evId, View.VISIBLE)
            if (overflow) {
                views.setTextViewText(evId, "+${n - 2}개")
                views.setTextColor(evId, mutedCol)
                continue
            }
            val e = events[k]
            val bar = if (e.done) mutedCol else barColor(context, e.color)
            val sb = SpannableStringBuilder("▍ ").append(e.title)
            sb.setSpan(ForegroundColorSpan(bar), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (e.done) {
                sb.setSpan(StrikethroughSpan(), 2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(mutedCol), 2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            views.setTextViewText(evId, sb)
            views.setTextColor(evId, textCol)
        }
    }

    private fun bindClicks(context: Context, views: RemoteViews, id: Int) {
        views.setOnClickPendingIntent(R.id.nav_prev, broadcast(context, id, ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.nav_next, broadcast(context, id, ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.nav_today, broadcast(context, id, ACTION_TODAY, 3))
        views.setOnClickPendingIntent(R.id.widget_refresh, broadcast(context, id, ACTION_REFRESH, 4))

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/schedule")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(context, id * 10 + 5, open, Widgets.piFlags(false))
        views.setOnClickPendingIntent(R.id.widget_title, openPi)
        views.setOnClickPendingIntent(R.id.grid, openPi)
    }

    private fun broadcast(context: Context, id: Int, action: String, code: Int): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id * 10 + code, intent, Widgets.piFlags(false))
    }

    private fun fetchSchedules(token: String, from: String, to: String): Map<String, List<Ev>>? {
        val body = Api.get("/schedules?from=$from&to=$to", token) ?: return null
        return try {
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyMap()
            val map = HashMap<String, MutableList<Ev>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("scheduled_date", "")
                if (date.isBlank()) continue
                map.getOrPut(date) { ArrayList() }.add(
                    Ev(
                        title = o.optString("title", "(제목 없음)"),
                        color = o.optInt("color", 1),
                        done = o.optBoolean("is_done", false)
                    )
                )
            }
            map
        } catch (e: Exception) {
            null
        }
    }

    private fun weekStartsOnSunday(context: Context, token: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (token != null) {
            val body = Api.get("/auth/me", token)
            if (body != null) {
                try {
                    val value = JSONObject(body).optJSONObject("data")
                        ?.optJSONObject("preferences")
                        ?.optString("schedule_week_starts_on", "")
                    if (!value.isNullOrBlank()) {
                        prefs.edit().putString(KEY_WEEK_START, value).apply()
                        return value == "sunday"
                    }
                } catch (e: Exception) {
                }
            }
        }
        return prefs.getString(KEY_WEEK_START, "sunday") == "sunday"
    }

    private fun weekdayColor(context: Context, label: String): Int = when (label) {
        "일" -> ContextCompat.getColor(context, R.color.widget_rose)
        "토" -> ContextCompat.getColor(context, R.color.widget_accent)
        else -> ContextCompat.getColor(context, R.color.widget_text_muted)
    }

    private fun barColor(context: Context, slot: Int): Int = when (slot) {
        2 -> Color.parseColor("#2563EB")
        3 -> Color.parseColor("#DC2626")
        4 -> Color.parseColor("#F0E442")
        5 -> Color.parseColor("#0072B2")
        6 -> Color.parseColor("#D55E00")
        7 -> Color.parseColor("#CC79A7")
        8 -> Color.parseColor("#7C3AED")
        else -> ContextCompat.getColor(context, R.color.widget_text)
    }

    private fun idOf(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "id", context.packageName)

    private fun keyOffset(id: Int) = "off_$id"

    private data class Ev(val title: String, val color: Int, val done: Boolean)

    companion object {
        const val ACTION_REFRESH = "com.honestfamily.dashboard.ACTION_SCHEDULE_REFRESH"
        const val ACTION_PREV = "com.honestfamily.dashboard.ACTION_SCHEDULE_PREV"
        const val ACTION_NEXT = "com.honestfamily.dashboard.ACTION_SCHEDULE_NEXT"
        const val ACTION_TODAY = "com.honestfamily.dashboard.ACTION_SCHEDULE_TODAY"
        const val EXTRA_ID = "widget_id"

        private const val PREFS = "hf_dashboard"
        private const val KEY_WEEK_START = "schedule_week_starts_on"

        private val EXEC = Executors.newSingleThreadExecutor()
    }
}
