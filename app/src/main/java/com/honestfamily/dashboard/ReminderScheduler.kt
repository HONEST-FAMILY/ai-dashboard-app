package com.honestfamily.dashboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

// 웹 스케줄에 설정한 "알림 시점"(1분·10분·1시간·1일·7일 전)을 읽어 실제 폰 알림으로 예약한다.
// 앱을 열 때(로그인 토큰 동기화 뒤)와 재부팅 뒤에 다시 맞춘다.
object ReminderScheduler {
    private const val PREFS = "hf_dashboard"
    private const val KEY_SCHEDULED = "reminder_codes"
    private const val DAYS_AHEAD = 14
    private val EXEC = Executors.newSingleThreadExecutor()

    fun sync(context: Context) {
        val appCtx = context.applicationContext
        val token = AppConfig.widgetToken(appCtx) ?: return
        EXEC.execute {
            try {
                doSync(appCtx, token)
            } catch (e: Exception) {
                // 네트워크·파싱 실패는 조용히 넘어간다 — 다음 실행에서 다시 맞춘다.
            }
        }
    }

    private fun doSync(context: Context, token: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 지난번에 예약해둔 알람을 모두 취소하고 새로 맞춘다(일정이 바뀌었을 수 있으니).
        val prev = prefs.getString(KEY_SCHEDULED, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        for (code in prev) {
            val rc = code.toIntOrNull() ?: continue
            am.cancel(pendingFor(context, rc, null, null))
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val today = Calendar.getInstance()
        val from = fmt.format(today.time)
        val toCal = today.clone() as Calendar
        toCal.add(Calendar.DAY_OF_YEAR, DAYS_AHEAD)
        val to = fmt.format(toCal.time)

        val body = Api.get("/schedules?from=$from&to=$to", token) ?: return
        val arr = JSONObject(body).optJSONArray("data") ?: return

        val now = System.currentTimeMillis()
        val newCodes = ArrayList<String>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("is_done", false)) continue
            val reminders = o.optJSONArray("reminders") ?: continue
            if (reminders.length() == 0) continue
            val dateStr = o.optString("scheduled_date", "")
            val parts = dateStr.split("-")
            if (parts.size != 3) continue
            val title = o.optString("title", "일정")
            val allDay = o.isNull("start_minute")
            val startMinute = if (allDay) 9 * 60 else o.optInt("start_minute", 9 * 60)
            val id = o.optInt("id", 0)

            val cal = Calendar.getInstance()
            cal.set(parts[0].toIntOrNull() ?: continue, (parts[1].toIntOrNull() ?: continue) - 1, parts[2].toIntOrNull() ?: continue, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val baseMillis = cal.timeInMillis + startMinute * 60_000L

            for (j in 0 until reminders.length()) {
                val r = reminders.optInt(j, -1)
                if (r < 0) continue
                val fireAt = baseMillis - r * 60_000L
                if (fireAt <= now) continue
                val rc = requestCode(id, r)
                val text = reminderText(r, startMinute, allDay)
                val pi = pendingFor(context, rc, title, text)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                    } else {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                    }
                } catch (e: Exception) {
                    am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
                }
                newCodes.add(rc.toString())
            }
        }
        prefs.edit().putString(KEY_SCHEDULED, newCodes.joinToString(",")).apply()
    }

    private fun requestCode(id: Int, reminder: Int): Int =
        (id * 100000 + reminder).hashCode() and 0x7fffffff

    private fun pendingFor(context: Context, rc: Int, title: String?, text: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.honestfamily.dashboard.REMINDER_$rc"
            if (title != null) putExtra(ReminderReceiver.EXTRA_TITLE, title)
            if (text != null) putExtra(ReminderReceiver.EXTRA_TEXT, text)
            putExtra(ReminderReceiver.EXTRA_NOTIF_ID, rc)
        }
        return PendingIntent.getBroadcast(context, rc, intent, Widgets.piFlags(false))
    }

    private fun reminderText(r: Int, startMinute: Int, allDay: Boolean): String {
        val lead = when {
            r == 0 -> "곧"
            r < 60 -> "${r}분 뒤"
            r < 1440 -> "${r / 60}시간 뒤"
            else -> "${r / 1440}일 뒤"
        }
        if (allDay) return "$lead 예정된 일정이에요"
        val timeStr = String.format(Locale.KOREA, "%02d:%02d", startMinute / 60, startMinute % 60)
        return "$lead 시작 ($timeStr)"
    }
}
