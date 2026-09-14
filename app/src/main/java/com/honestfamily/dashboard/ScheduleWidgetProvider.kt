package com.honestfamily.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
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

        val gridStart = month.clone() as Calendar
        val dowSun0 = gridStart.get(Calendar.DAY_OF_WEEK) - 1
        val back = if (sundayFirst) dowSun0 else (dowSun0 + 6) % 7
        gridStart.add(Calendar.DAY_OF_YEAR, -back)

        // 요일 헤더는 이제 레이아웃(TextView)로 그린다 — 격자 셀을 눌러 그 날 일정을 열 수 있게 하려고 캔버스는 격자만 그린다.
        val weekdayLabels = if (sundayFirst)
            arrayOf("일", "월", "화", "수", "목", "금", "토")
        else
            arrayOf("월", "화", "수", "목", "금", "토", "일")
        for (i in 0 until 7) {
            val wdId = context.resources.getIdentifier("wd_$i", "id", context.packageName)
            if (wdId != 0) {
                views.setTextViewText(wdId, weekdayLabels[i])
                val col = when (weekdayLabels[i]) {
                    "일" -> R.color.widget_rose
                    "토" -> R.color.widget_accent
                    else -> R.color.widget_text_muted
                }
                views.setTextColor(wdId, ContextCompat.getColor(context, col))
            }
        }

        val from = fmt.format(gridStart.time)
        val toCal = gridStart.clone() as Calendar
        toCal.add(Calendar.DAY_OF_YEAR, 41)
        val to = fmt.format(toCal.time)

        val byDate: Map<String, List<Ev>>? = if (token == null) null else fetchSchedules(token, from, to)

        if (byDate == null) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setViewVisibility(R.id.widget_canvas, View.GONE)
            views.setTextViewText(R.id.widget_empty, context.getString(R.string.need_login))
        } else {
            val bitmap = drawCalendar(context, mgr, id, year, mon0, sundayFirst, todayStr, gridStart, byDate, fmt)
            views.setImageViewBitmap(R.id.widget_canvas, bitmap)
            views.setViewVisibility(R.id.widget_canvas, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
        }

        bindClicks(context, views, id, gridStart, fmt)
        mgr.updateAppWidget(id, views)
    }

    private fun drawCalendar(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        year: Int,
        mon0: Int,
        sundayFirst: Boolean,
        todayStr: String,
        gridStart: Calendar,
        byDate: Map<String, List<Ev>>,
        fmt: SimpleDateFormat
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val opts = mgr.getAppWidgetOptions(id)
        val minWidthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxHeightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val wDp = if (minWidthDp > 0) minWidthDp else 250
        val hDp = if (maxHeightDp > 0) maxHeightDp else 240
        val w = ((wDp - 24) * density).toInt().coerceIn(240, 1400)
        val h = ((hDp - 66) * density).toInt().coerceIn(180, 1600)

        val textCol = ContextCompat.getColor(context, R.color.widget_text)
        val mutedCol = ContextCompat.getColor(context, R.color.widget_text_muted)
        val outCol = ContextCompat.getColor(context, R.color.widget_out)
        val roseCol = ContextCompat.getColor(context, R.color.widget_rose)
        val accentCol = ContextCompat.getColor(context, R.color.widget_accent)
        val todayTextCol = ContextCompat.getColor(context, R.color.widget_today_text)
        val borderCol = ContextCompat.getColor(context, R.color.widget_border)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pad = density * 3f
        val colW = w / 7f
        val rowH = h / 6f

        val dateSize = (rowH * 0.26f).coerceIn(density * 10f, density * 15f)
        val evSize = (rowH * 0.19f).coerceIn(density * 8f, density * 11.5f)
        val evLineH = evSize * 1.42f
        val dateR = dateSize * 0.82f
        val barW = density * 3f

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dateSize
            textAlign = Paint.Align.CENTER
        }
        val evPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = evSize }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderCol
            strokeWidth = Math.max(1f, density * 0.5f)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val dateFm = datePaint.fontMetrics
        val evFm = evPaint.fontMetrics
        val dateAreaH = dateR * 2f + pad
        val maxLines = (((rowH - dateAreaH - pad) / evLineH).toInt()).coerceIn(0, 3)

        val cell = gridStart.clone() as Calendar
        for (i in 0 until 42) {
            val r = i / 7
            val c = i % 7
            val x0 = colW * c
            val y0 = rowH * r
            val ds = fmt.format(cell.time)
            val inMonth = cell.get(Calendar.MONTH) == mon0 && cell.get(Calendar.YEAR) == year
            val isToday = ds == todayStr
            val isSunday = cell.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

            if (r > 0) canvas.drawLine(x0, y0, x0 + colW, y0, linePaint)

            val cx = x0 + pad + dateR
            val cy = y0 + pad + dateR
            val dateStr = cell.get(Calendar.DAY_OF_MONTH).toString()
            if (isToday) {
                fillPaint.color = accentCol
                canvas.drawCircle(cx, cy, dateR, fillPaint)
                datePaint.color = todayTextCol
            } else {
                datePaint.color = if (!inMonth) outCol else if (isSunday) roseCol else textCol
            }
            canvas.drawText(dateStr, cx, cy - (dateFm.ascent + dateFm.descent) / 2f, datePaint)

            val events = byDate[ds] ?: emptyList()
            val n = events.size
            val evsTop = y0 + dateAreaH
            val evAvail = colW - pad * 2f - barW - pad
            for (k in 0 until maxLines) {
                val lineTop = evsTop + evLineH * k
                val baseline = lineTop + evLineH / 2f - (evFm.ascent + evFm.descent) / 2f
                val overflow = n > maxLines && k == maxLines - 1
                if (overflow) {
                    evPaint.isStrikeThruText = false
                    evPaint.color = mutedCol
                    canvas.drawText("+${n - (maxLines - 1)}개", x0 + pad, baseline, evPaint)
                    continue
                }
                if (k >= n) continue
                val e = events[k]
                val bar = if (e.done) mutedCol else barColor(context, e.color)
                fillPaint.color = bar
                val barTop = lineTop + evLineH * 0.18f
                canvas.drawRoundRect(
                    RectF(x0 + pad, barTop, x0 + pad + barW, barTop + evSize),
                    barW / 2f, barW / 2f, fillPaint
                )
                evPaint.isStrikeThruText = e.done
                evPaint.color = if (e.done) mutedCol else textCol
                val clipped = TextUtils.ellipsize(e.title, evPaint, evAvail, TextUtils.TruncateAt.END)
                canvas.drawText(clipped, 0, clipped.length, x0 + pad + barW + pad, baseline, evPaint)
            }
            cell.add(Calendar.DAY_OF_YEAR, 1)
        }
        return bitmap
    }

    private fun bindClicks(context: Context, views: RemoteViews, id: Int, gridStart: Calendar, fmt: SimpleDateFormat) {
        views.setOnClickPendingIntent(R.id.nav_prev, broadcast(context, id, ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.nav_next, broadcast(context, id, ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.nav_today, broadcast(context, id, ACTION_TODAY, 3))
        views.setOnClickPendingIntent(R.id.widget_refresh, broadcast(context, id, ACTION_REFRESH, 4))

        val openPi = PendingIntent.getActivity(context, id * 10 + 5, openScheduleIntent(context, "/schedule"), Widgets.piFlags(false))
        views.setOnClickPendingIntent(R.id.widget_title, openPi)
        views.setOnClickPendingIntent(R.id.widget_empty, openPi)

        // 우측 상단 + 버튼 — 새 일정 바로 등록
        val addPi = PendingIntent.getActivity(context, id * 1000 + 99, openScheduleIntent(context, "/schedule?new=1"), Widgets.piFlags(false))
        views.setOnClickPendingIntent(R.id.nav_add, addPi)

        // 날짜 셀을 누르면 그 날 일정 화면을 연다(구글 캘린더처럼).
        val cell = gridStart.clone() as Calendar
        for (i in 0 until 42) {
            val ds = fmt.format(cell.time)
            val cellId = context.resources.getIdentifier("cell_$i", "id", context.packageName)
            if (cellId != 0) {
                val pi = PendingIntent.getActivity(context, id * 1000 + 100 + i, openScheduleIntent(context, "/schedule?date=$ds"), Widgets.piFlags(false))
                views.setOnClickPendingIntent(cellId, pi)
            }
            cell.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    // 같은 액티비티라도 날짜마다 다른 화면으로 열리도록 경로를 액션에 실어 PendingIntent가 구분되게 한다.
    private fun openScheduleIntent(context: Context, path: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = "com.honestfamily.dashboard.OPEN_" + path.hashCode()
            putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + path)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
