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
import android.net.Uri
import android.os.Bundle
import android.text.StaticLayout
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
        for (id in appWidgetIds) {
            editor.remove(keyOffset(id))
            editor.remove(keyWeekOffset(id))
            editor.remove(keyTtDate(id))
            editor.remove(keyMode(id))
            editor.remove(keySelDate(id))
        }
        editor.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY, ACTION_REFRESH -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val cur = prefs.getInt(keyOffset(id), 0)
                    when (intent.action) {
                        ACTION_PREV -> prefs.edit().putInt(keyOffset(id), cur - 1).apply()
                        ACTION_NEXT -> prefs.edit().putInt(keyOffset(id), cur + 1).apply()
                        ACTION_TODAY -> prefs.edit().putInt(keyOffset(id), 0).apply()
                    }
                    renderAsync(context, intArrayOf(id))
                }
            }
            ACTION_OPEN_DAY -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val date = intent.getStringExtra(EXTRA_DATE)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && date != null) {
                    prefs.edit().putString(keyMode(id), MODE_DAY).putString(keySelDate(id), date).apply()
                    renderAsync(context, intArrayOf(id))
                }
            }
            ACTION_BACK_MONTH -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    prefs.edit().putString(keyMode(id), MODE_MONTH).apply()
                    renderAsync(context, intArrayOf(id))
                }
            }
            ACTION_SHOW_WEEK -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    prefs.edit().putString(keyMode(id), MODE_WEEK).putInt(keyWeekOffset(id), 0).apply()
                    renderAsync(context, intArrayOf(id))
                }
            }
            ACTION_WEEK_PREV, ACTION_WEEK_NEXT, ACTION_WEEK_TODAY -> {
                val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val cur = prefs.getInt(keyWeekOffset(id), 0)
                    when (intent.action) {
                        ACTION_WEEK_PREV -> prefs.edit().putInt(keyWeekOffset(id), cur - 1).apply()
                        ACTION_WEEK_NEXT -> prefs.edit().putInt(keyWeekOffset(id), cur + 1).apply()
                        ACTION_WEEK_TODAY -> prefs.edit().putInt(keyWeekOffset(id), 0).apply()
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
        val token = AppConfig.widgetToken(context)
        val mode = prefs.getString(keyMode(id), MODE_MONTH)

        setupDayList(context, views, id)
        bindHeaderClicks(context, views, id)

        val dayMode = token != null && mode == MODE_DAY
        val weekMode = token != null && mode == MODE_WEEK
        when {
            weekMode -> {
                renderWeek(context, id, views, token, prefs)
                showWeek(views)
            }
            dayMode -> {
                renderDay(context, views, id, prefs)
                showDay(views)
            }
            else -> {
                renderMonth(context, mgr, id, views, token, prefs)
                showMonth(views)
            }
        }

        mgr.updateAppWidget(id, views)
        if (dayMode) mgr.notifyAppWidgetViewDataChanged(id, R.id.day_list)
        if (weekMode) mgr.notifyAppWidgetViewDataChanged(id, R.id.week_list)
    }

    private fun renderMonth(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        views: RemoteViews,
        token: String?,
        prefs: android.content.SharedPreferences
    ) {
        val offset = prefs.getInt(keyOffset(id), 0)

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
                    "토" -> R.color.widget_sat
                    else -> R.color.widget_text_muted
                }
                views.setTextColor(wdId, ContextCompat.getColor(context, col))
            }
        }

        val from = fmt.format(gridStart.time)
        val toCal = gridStart.clone() as Calendar
        toCal.add(Calendar.DAY_OF_YEAR, 41)
        val to = fmt.format(toCal.time)

        val result = if (token == null) FetchResult.AuthFailed else fetchSchedules(context, id, token, from, to)

        if (result is FetchResult.AuthFailed) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setViewVisibility(R.id.widget_canvas, View.GONE)
            views.setTextViewText(R.id.widget_empty, context.getString(R.string.need_login))
        } else {
            val byDate = if (result is FetchResult.Ok) result.byDate else emptyMap()
            val bitmap = drawCalendar(context, mgr, id, year, mon0, sundayFirst, todayStr, gridStart, byDate, fmt)
            views.setImageViewBitmap(R.id.widget_canvas, bitmap)
            views.setViewVisibility(R.id.widget_canvas, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
        }

        bindCellClicks(context, views, id, gridStart, fmt)
    }

    private fun renderDay(context: Context, views: RemoteViews, id: Int, prefs: android.content.SharedPreferences) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val todayStr = fmt.format(Calendar.getInstance().time)
        val date = prefs.getString(keySelDate(id), todayStr) ?: todayStr

        val label = try {
            val parsed = fmt.parse(date)
            SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(parsed!!)
        } catch (e: Exception) {
            date
        }
        views.setTextViewText(R.id.day_title, label)

        val addPi = PendingIntent.getActivity(
            context, id * 1000 + 200,
            openScheduleIntent(context, "/schedule?date=$date&new=1"),
            Widgets.piFlags(false)
        )
        views.setOnClickPendingIntent(R.id.day_add, addPi)
        views.setOnClickPendingIntent(R.id.day_empty, addPi)
    }

    private fun renderWeek(
        context: Context,
        id: Int,
        views: RemoteViews,
        token: String?,
        prefs: android.content.SharedPreferences
    ) {
        val offset = prefs.getInt(keyWeekOffset(id), 0)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val date = fmt.format(day.time)
        prefs.edit().putString(keyTtDate(id), date).apply()
        views.setTextViewText(R.id.week_title, SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(day.time))

        setupTimetableList(context, views, id)

        if (token == null) {
            views.setTextViewText(R.id.week_empty, context.getString(R.string.need_login))
        }
    }

    private fun setupTimetableList(context: Context, views: RemoteViews, id: Int) {
        val svc = Intent(context, ScheduleTimetableWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.week_list, svc)
        views.setEmptyView(R.id.week_list, R.id.week_empty)

        val template = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setPendingIntentTemplate(
            R.id.week_list,
            PendingIntent.getActivity(context, id * 10 + 9, template, Widgets.piFlags(true))
        )
    }

    private fun setupDayList(context: Context, views: RemoteViews, id: Int) {
        val svc = Intent(context, ScheduleDayWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.day_list, svc)
        views.setEmptyView(R.id.day_list, R.id.day_empty)

        val template = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setPendingIntentTemplate(
            R.id.day_list,
            PendingIntent.getActivity(context, id * 10 + 6, template, Widgets.piFlags(true))
        )
    }

    private fun bindHeaderClicks(context: Context, views: RemoteViews, id: Int) {
        views.setOnClickPendingIntent(R.id.nav_prev, broadcast(context, id, ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.nav_next, broadcast(context, id, ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.nav_today, broadcast(context, id, ACTION_TODAY, 3))
        views.setOnClickPendingIntent(R.id.widget_refresh, broadcast(context, id, ACTION_REFRESH, 4))
        views.setOnClickPendingIntent(R.id.day_refresh, broadcast(context, id, ACTION_REFRESH, 4))
        views.setOnClickPendingIntent(R.id.day_back, broadcast(context, id, ACTION_BACK_MONTH, 7))
        views.setOnClickPendingIntent(R.id.mode_timetable, broadcast(context, id, ACTION_SHOW_WEEK, 8))

        views.setOnClickPendingIntent(R.id.week_back, broadcast(context, id, ACTION_BACK_MONTH, 7))
        views.setOnClickPendingIntent(R.id.week_prev, weekBroadcast(context, id, ACTION_WEEK_PREV, 1))
        views.setOnClickPendingIntent(R.id.week_next, weekBroadcast(context, id, ACTION_WEEK_NEXT, 2))
        views.setOnClickPendingIntent(R.id.week_today, weekBroadcast(context, id, ACTION_WEEK_TODAY, 3))
        views.setOnClickPendingIntent(R.id.week_refresh, broadcast(context, id, ACTION_REFRESH, 4))

        val openPi = PendingIntent.getActivity(context, id * 10 + 5, openScheduleIntent(context, "/schedule"), Widgets.piFlags(false))
        views.setOnClickPendingIntent(R.id.widget_title, openPi)

        val addPi = PendingIntent.getActivity(context, id * 1000 + 99, openScheduleIntent(context, "/schedule?new=1"), Widgets.piFlags(false))
        views.setOnClickPendingIntent(R.id.nav_add, addPi)
    }

    private fun showMonth(views: RemoteViews) {
        views.setViewVisibility(R.id.month_header, View.VISIBLE)
        views.setViewVisibility(R.id.weekday_row, View.VISIBLE)
        views.setViewVisibility(R.id.month_body, View.VISIBLE)
        views.setViewVisibility(R.id.day_header, View.GONE)
        views.setViewVisibility(R.id.day_body, View.GONE)
        views.setViewVisibility(R.id.week_header, View.GONE)
        views.setViewVisibility(R.id.week_body, View.GONE)
    }

    private fun showDay(views: RemoteViews) {
        views.setViewVisibility(R.id.month_header, View.GONE)
        views.setViewVisibility(R.id.weekday_row, View.GONE)
        views.setViewVisibility(R.id.month_body, View.GONE)
        views.setViewVisibility(R.id.day_header, View.VISIBLE)
        views.setViewVisibility(R.id.day_body, View.VISIBLE)
        views.setViewVisibility(R.id.week_header, View.GONE)
        views.setViewVisibility(R.id.week_body, View.GONE)
    }

    private fun showWeek(views: RemoteViews) {
        views.setViewVisibility(R.id.month_header, View.GONE)
        views.setViewVisibility(R.id.weekday_row, View.GONE)
        views.setViewVisibility(R.id.month_body, View.GONE)
        views.setViewVisibility(R.id.day_header, View.GONE)
        views.setViewVisibility(R.id.day_body, View.GONE)
        views.setViewVisibility(R.id.week_header, View.VISIBLE)
        views.setViewVisibility(R.id.week_body, View.VISIBLE)
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
        val satCol = ContextCompat.getColor(context, R.color.widget_sat)
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
        val evGap = density * 2f

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
            val isSaturday = cell.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY

            if (r > 0) canvas.drawLine(x0, y0, x0 + colW, y0, linePaint)

            val cx = x0 + pad + dateR
            val cy = y0 + pad + dateR
            val dateStr = cell.get(Calendar.DAY_OF_MONTH).toString()
            if (isToday) {
                fillPaint.color = accentCol
                val boxR = dateR * 0.42f
                canvas.drawRoundRect(RectF(cx - dateR, cy - dateR, cx + dateR, cy + dateR), boxR, boxR, fillPaint)
                datePaint.color = todayTextCol
            } else {
                datePaint.color = if (!inMonth) outCol else if (isSunday) roseCol else if (isSaturday) satCol else textCol
            }
            canvas.drawText(dateStr, cx, cy - (dateFm.ascent + dateFm.descent) / 2f, datePaint)

            val events = byDate[ds] ?: emptyList()
            val evsTop = y0 + dateAreaH
            val evsBottom = y0 + rowH - pad
            val textLeft = x0 + pad + barW + pad
            val evAvail = (colW - pad * 2f - barW - pad).toInt().coerceAtLeast(1)

            var yCursor = evsTop
            var drawn = 0
            var k = 0
            while (k < events.size) {
                val avail = evsBottom - yCursor
                if (avail < evLineH * 0.95f) break

                val e = events[k]
                val lines = if (avail >= evLineH * 1.9f) 2 else 1
                val layout = StaticLayout.Builder
                    .obtain(e.title, 0, e.title.length, evPaint, evAvail)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(false)
                    .build()
                val blockH = layout.height.toFloat()

                val moreAfterThis = events.size - k > 1
                val roomAfter = (evsBottom - (yCursor + blockH + evGap)) >= evLineH * 0.95f
                if (drawn > 0 && moreAfterThis && !roomAfter) {
                    evPaint.color = mutedCol
                    val baseline = yCursor + evLineH / 2f - (evFm.ascent + evFm.descent) / 2f
                    canvas.drawText("+${events.size - k}개", x0 + pad, baseline, evPaint)
                    break
                }

                fillPaint.color = barColor(context, e.color)
                canvas.drawRoundRect(
                    RectF(x0 + pad, yCursor + density * 1.5f, x0 + pad + barW, yCursor + blockH - density * 1.5f),
                    barW / 2f, barW / 2f, fillPaint
                )
                evPaint.color = textCol
                canvas.save()
                canvas.translate(textLeft, yCursor)
                layout.draw(canvas)
                canvas.restore()

                yCursor += blockH + evGap
                drawn++
                k++
            }
            cell.add(Calendar.DAY_OF_YEAR, 1)
        }
        return bitmap
    }

    private fun bindCellClicks(context: Context, views: RemoteViews, id: Int, gridStart: Calendar, fmt: SimpleDateFormat) {
        val cell = gridStart.clone() as Calendar
        for (i in 0 until 42) {
            val ds = fmt.format(cell.time)
            val cellId = context.resources.getIdentifier("cell_$i", "id", context.packageName)
            if (cellId != 0) {
                views.setOnClickPendingIntent(cellId, broadcastOpenDay(context, id, ds, i))
            }
            cell.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

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

    private fun weekBroadcast(context: Context, id: Int, action: String, n: Int): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id * 1000 + 300 + n, intent, Widgets.piFlags(false))
    }

    private fun broadcastOpenDay(context: Context, id: Int, date: String, index: Int): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java)
            .setAction(ACTION_OPEN_DAY)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_DATE, date)
        return PendingIntent.getBroadcast(context, id * 1000 + 100 + index, intent, Widgets.piFlags(false))
    }

    private fun fetchSchedules(context: Context, id: Int, token: String, from: String, to: String): FetchResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val range = "$from|$to"
        val res = Api.request("/schedules?from=$from&to=$to&mine=1&is_done=0", token)

        if (res.code == 200 && res.body != null) {
            val map = parseSchedules(res.body) ?: return FetchResult.Unavailable
            prefs.edit().putString(keyCacheRange(id), range).putString(keyCacheBody(id), res.body).apply()
            return FetchResult.Ok(map)
        }

        if (res.code == 401) return FetchResult.AuthFailed

        if (prefs.getString(keyCacheRange(id), null) == range) {
            val cached = prefs.getString(keyCacheBody(id), null)
            if (cached != null) parseSchedules(cached)?.let { return FetchResult.Ok(it) }
        }
        return FetchResult.Unavailable
    }

    private fun parseSchedules(body: String): Map<String, List<Ev>>? {
        return try {
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyMap()
            val map = HashMap<String, MutableList<Ev>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("scheduled_date", "")
                if (date.isBlank()) continue
                if (o.optBoolean("is_done", false)) continue
                map.getOrPut(date) { ArrayList() }.add(
                    Ev(
                        title = o.optString("title", "(제목 없음)"),
                        color = o.optInt("color", 1)
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
    private fun keyWeekOffset(id: Int) = "woff_$id"
    private fun keyTtDate(id: Int) = "ttdate_$id"
    private fun keyMode(id: Int) = "mode_$id"
    private fun keySelDate(id: Int) = "seldate_$id"
    private fun keyCacheRange(id: Int) = "sched_range_$id"
    private fun keyCacheBody(id: Int) = "sched_body_$id"

    private data class Ev(val title: String, val color: Int)

    private sealed class FetchResult {
        data class Ok(val byDate: Map<String, List<Ev>>) : FetchResult()
        object AuthFailed : FetchResult()
        object Unavailable : FetchResult()
    }

    companion object {
        const val ACTION_REFRESH = "com.honestfamily.dashboard.ACTION_SCHEDULE_REFRESH"
        const val ACTION_PREV = "com.honestfamily.dashboard.ACTION_SCHEDULE_PREV"
        const val ACTION_NEXT = "com.honestfamily.dashboard.ACTION_SCHEDULE_NEXT"
        const val ACTION_TODAY = "com.honestfamily.dashboard.ACTION_SCHEDULE_TODAY"
        const val ACTION_OPEN_DAY = "com.honestfamily.dashboard.ACTION_SCHEDULE_OPEN_DAY"
        const val ACTION_BACK_MONTH = "com.honestfamily.dashboard.ACTION_SCHEDULE_BACK_MONTH"
        const val ACTION_SHOW_WEEK = "com.honestfamily.dashboard.ACTION_SCHEDULE_SHOW_WEEK"
        const val ACTION_WEEK_PREV = "com.honestfamily.dashboard.ACTION_SCHEDULE_WEEK_PREV"
        const val ACTION_WEEK_NEXT = "com.honestfamily.dashboard.ACTION_SCHEDULE_WEEK_NEXT"
        const val ACTION_WEEK_TODAY = "com.honestfamily.dashboard.ACTION_SCHEDULE_WEEK_TODAY"
        const val EXTRA_ID = "widget_id"
        const val EXTRA_DATE = "widget_date"

        private const val PREFS = "hf_dashboard"
        private const val KEY_WEEK_START = "schedule_week_starts_on"
        private const val MODE_MONTH = "month"
        private const val MODE_DAY = "day"
        private const val MODE_WEEK = "week"

        private val EXEC = Executors.newSingleThreadExecutor()
    }
}
