package com.honestfamily.dashboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// 예약해둔 시각이 되면 울리는 알림. AlarmManager가 이 리시버를 깨운다.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "일정 알림"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 1)

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "일정 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "예정된 일정을 미리 알려줍니다"
            }
            mgr.createNotificationChannel(channel)
        }

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/schedule")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, notifId, open, Widgets.piFlags(false))

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_schedule)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(context, R.color.widget_accent))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            mgr.notify(notifId, notif)
        } catch (e: SecurityException) {
            // 알림 권한이 없으면 조용히 넘어간다.
        }
    }

    companion object {
        const val CHANNEL_ID = "schedule_reminders"
        const val EXTRA_TITLE = "notif_title"
        const val EXTRA_TEXT = "notif_text"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
