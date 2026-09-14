package com.honestfamily.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 재부팅하면 예약해둔 알람이 사라지므로 다시 맞춘다.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.sync(context)
        }
    }
}
