package com.honestfamily.dashboard.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.min

class FocusView(context: Context) : View(context) {

    enum class Status { LOADING, NEED_TOKEN, ERROR, EMPTY, OK }

    class Item(val title: String, val color: Int, val start: Int, val end: Int?, val project: String?)

    var onRefresh: (() -> Unit)? = null

    private var status: Status = Status.LOADING
    private var items: List<Item> = emptyList()
    private var allDayTitles: List<String> = emptyList()
    private var selectedIndex: Int = -1
    private var userNavigated: Boolean = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setData(newItems: List<Item>, newAllDay: List<String>) {
        items = newItems
        allDayTitles = newAllDay
        userNavigated = false
        status = if (newItems.isEmpty()) Status.EMPTY else Status.OK
        selectedIndex = computeFocusIndex(nowSeconds())
        invalidate()
    }

    fun setStatus(s: Status) {
        status = s
        invalidate()
    }

    fun tick() {
        if (status == Status.OK && !userNavigated) {
            selectedIndex = computeFocusIndex(nowSeconds())
        }
        invalidate()
    }

    private fun prev() {
        if (status != Status.OK) return
        if (selectedIndex > 0) {
            selectedIndex--
            userNavigated = true
            invalidate()
        }
    }

    private fun next() {
        if (status != Status.OK) return
        if (selectedIndex < items.size - 1) {
            selectedIndex++
            userNavigated = true
            invalidate()
        }
    }

    private fun resetToNow() {
        userNavigated = false
        if (status == Status.OK) selectedIndex = computeFocusIndex(nowSeconds())
        invalidate()
        onRefresh?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val x = event.x
        val w = width.toFloat()
        when {
            x < w * 0.28f -> prev()
            x > w * 0.72f -> next()
            else -> resetToNow()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta > 0f) next() else if (delta < 0f) prev()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun nowSeconds(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
    }

    private fun effEnd(item: Item): Int = item.end ?: (item.start + 60)

    private fun computeFocusIndex(nowSec: Int): Int {
        if (items.isEmpty()) return -1
        var active = -1
        var activeEnd = Int.MAX_VALUE
        var upcoming = -1
        var upcomingStart = Int.MAX_VALUE
        for (i in items.indices) {
            val s = items[i].start * 60
            val e = effEnd(items[i]) * 60
            if (nowSec in s until e) {
                if (e < activeEnd) { activeEnd = e; active = i }
            } else if (s > nowSec && s < upcomingStart) {
                upcomingStart = s; upcoming = i
            }
        }
        return when {
            active >= 0 -> active
            upcoming >= 0 -> upcoming
            else -> items.size - 1
        }
    }

    private fun slotColor(slot: Int): Int = when (slot) {
        2 -> Color.parseColor("#2563EB")
        3 -> Color.parseColor("#DC2626")
        4 -> Color.parseColor("#F0E442")
        5 -> Color.parseColor("#0072B2")
        6 -> Color.parseColor("#D55E00")
        7 -> Color.parseColor("#CC79A7")
        8 -> Color.parseColor("#7C3AED")
        else -> Color.parseColor("#E7EAE3")
    }

    private fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return String.format("%d:%02d", m / 60, m % 60)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f

        fill.color = Color.parseColor("#0B0F0A")
        canvas.drawRect(0f, 0f, w, h, fill)

        when (status) {
            Status.LOADING -> { drawCenterMessage(canvas, cx, cy, r, "불러오는 중…", null); return }
            Status.NEED_TOKEN -> { drawCenterMessage(canvas, cx, cy, r, "폰 앱을 먼저 열어주세요", "로그인하면 워치에 연결됩니다"); return }
            Status.ERROR -> { drawCenterMessage(canvas, cx, cy, r, "연결 안 됨", "가운데를 눌러 다시 시도"); return }
            Status.EMPTY -> {
                val sub = if (allDayTitles.isEmpty()) "가운데를 눌러 새로고침"
                else allDayTitles.take(2).joinToString(" · ")
                drawCenterMessage(canvas, cx, cy, r, "오늘 시간표가 없어요", sub); return
            }
            Status.OK -> {}
        }

        val idx = selectedIndex.coerceIn(0, items.size - 1)
        val item = items[idx]
        val nowSec = nowSeconds()
        val startSec = item.start * 60
        val endSec = effEnd(item) * 60
        val total = (endSec - startSec).coerceAtLeast(1)

        val active = nowSec in startSec until endSec
        val upcoming = nowSec < startSec
        val fraction = when {
            active -> (endSec - nowSec).toFloat() / total.toFloat()
            upcoming -> 1f
            else -> 0f
        }.coerceIn(0f, 1f)

        val color = slotColor(item.color)
        val strokeW = r * 0.11f
        val ringR = r - strokeW / 2f - r * 0.03f
        val oval = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)

        arc.strokeWidth = strokeW
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = Color.parseColor("#20261C")
        canvas.drawArc(oval, 0f, 360f, false, arc)

        if (fraction > 0f) {
            arc.color = if (upcoming) dim(color) else color
            canvas.drawArc(oval, -90f, 360f * fraction, false, arc)
        }

        val stateText: String
        val stateColor: Int
        when {
            active -> { stateText = "지금 할 일"; stateColor = Color.parseColor("#02955A") }
            upcoming -> { stateText = "다음 일정"; stateColor = Color.parseColor("#9AA39A") }
            else -> { stateText = "지난 일정"; stateColor = Color.parseColor("#9AA39A") }
        }

        text.color = stateColor
        text.textSize = r * 0.11f
        canvas.drawText(stateText, cx, cy - r * 0.46f, text)

        val innerW = r * 1.30f
        val (line1, line2) = wrapTwo(item.title, r * 0.135f, innerW)
        text.color = Color.WHITE
        text.textSize = r * 0.135f
        if (line2 == null) {
            canvas.drawText(line1, cx, cy - r * 0.16f, text)
        } else {
            canvas.drawText(line1, cx, cy - r * 0.24f, text)
            canvas.drawText(line2, cx, cy - r * 0.09f, text)
        }

        val big = when {
            active -> remainingLabel(endSec - nowSec)
            upcoming -> remainingLabel(startSec - nowSec) + " 후"
            else -> "종료됨"
        }
        text.color = if (active) color else Color.parseColor("#C7CDC3")
        text.textSize = r * 0.26f
        canvas.drawText(big, cx, cy + r * 0.16f, text)

        val range = if (item.end != null) "${fmt(item.start)} – ${fmt(item.end)}" else "${fmt(item.start)} 시작"
        text.color = Color.parseColor("#9AA39A")
        text.textSize = r * 0.115f
        canvas.drawText(range, cx, cy + r * 0.37f, text)

        text.color = Color.parseColor("#6E766C")
        text.textSize = r * 0.10f
        canvas.drawText("${idx + 1} / ${items.size}", cx, cy + r * 0.52f, text)

        text.textSize = r * 0.26f
        if (idx > 0) {
            text.color = Color.parseColor("#C7CDC3")
            canvas.drawText("‹", cx - r * 0.74f, cy + r * 0.09f, text)
        }
        if (idx < items.size - 1) {
            text.color = Color.parseColor("#C7CDC3")
            canvas.drawText("›", cx + r * 0.74f, cy + r * 0.09f, text)
        }
    }

    private fun remainingLabel(secLeft: Int): String {
        val s = secLeft.coerceAtLeast(0)
        if (s < 60) return "${s}초"
        val m = (s + 59) / 60
        return "${m}분"
    }

    private fun dim(color: Int): Int {
        val a = 0.55f
        val r = (Color.red(color) * a).toInt()
        val g = (Color.green(color) * a).toInt()
        val b = (Color.blue(color) * a).toInt()
        return Color.rgb(r, g, b)
    }

    private fun drawCenterMessage(canvas: Canvas, cx: Float, cy: Float, r: Float, title: String, sub: String?) {
        text.color = Color.WHITE
        text.textSize = r * 0.15f
        val (l1, l2) = wrapTwo(title, r * 0.15f, r * 1.3f)
        if (l2 == null) {
            canvas.drawText(l1, cx, cy - (if (sub != null) r * 0.02f else -r * 0.05f), text)
        } else {
            canvas.drawText(l1, cx, cy - r * 0.10f, text)
            canvas.drawText(l2, cx, cy + r * 0.06f, text)
        }
        if (sub != null) {
            text.color = Color.parseColor("#9AA39A")
            text.textSize = r * 0.11f
            canvas.drawText(ellipsize(sub, r * 0.11f, r * 1.4f), cx, cy + r * 0.24f, text)
        }
    }

    private fun wrapTwo(source: String, size: Float, maxWidth: Float): Pair<String, String?> {
        text.textSize = size
        if (text.measureText(source) <= maxWidth) return Pair(source, null)
        var cut = source.length
        for (i in 1..source.length) {
            if (text.measureText(source.substring(0, i)) > maxWidth) { cut = i - 1; break }
        }
        if (cut < 1) cut = 1
        val line1 = source.substring(0, cut)
        val rest = source.substring(cut)
        return Pair(line1, ellipsize(rest, size, maxWidth))
    }

    private fun ellipsize(source: String, size: Float, maxWidth: Float): String {
        text.textSize = size
        if (text.measureText(source) <= maxWidth) return source
        var s = source
        while (s.length > 1 && text.measureText("$s…") > maxWidth) {
            s = s.substring(0, s.length - 1)
        }
        return "$s…"
    }
}
