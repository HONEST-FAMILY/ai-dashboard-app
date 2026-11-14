package com.honestfamily.dashboard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationCollectorSettingsActivity : AppCompatActivity() {

    private lateinit var notificationsSwitch: SwitchCompat
    private lateinit var smsSwitch: SwitchCompat
    private lateinit var callsSwitch: SwitchCompat
    private lateinit var notificationsState: TextView
    private lateinit var smsState: TextView
    private lateinit var callsState: TextView
    private lateinit var notificationAccessButton: Button
    private lateinit var lastSyncText: TextView
    private lateinit var queueText: TextView
    private var rendering = false
    private val handler = Handler(Looper.getMainLooper())

    private val statusRefresher = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    private val smsPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val allowed = ConversationCollector.hasSms(this)
            ConversationCollector.setSmsEnabled(this, allowed)
            if (!allowed) showDenied()
            render()
        }

    private val callPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val allowed = ConversationCollector.hasCallLog(this) || ConversationCollector.hasAudio(this)
            ConversationCollector.setCallsEnabled(this, allowed)
            if (!allowed) showDenied()
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_collector_settings)
        notificationsSwitch = findViewById(R.id.switch_collect_notifications)
        smsSwitch = findViewById(R.id.switch_collect_sms)
        callsSwitch = findViewById(R.id.switch_collect_calls)
        notificationsState = findViewById(R.id.text_notifications_state)
        smsState = findViewById(R.id.text_sms_state)
        callsState = findViewById(R.id.text_calls_state)
        notificationAccessButton = findViewById(R.id.button_notification_access)
        lastSyncText = findViewById(R.id.text_last_sync)
        queueText = findViewById(R.id.text_queue)

        notificationsSwitch.setOnCheckedChangeListener { _, checked -> if (!rendering) onNotificationsToggled(checked) }
        smsSwitch.setOnCheckedChangeListener { _, checked -> if (!rendering) onSmsToggled(checked) }
        callsSwitch.setOnCheckedChangeListener { _, checked -> if (!rendering) onCallsToggled(checked) }
        notificationAccessButton.setOnClickListener { openNotificationAccess() }
        findViewById<Button>(R.id.button_open_app_info).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.button_sync_now).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.button_open_inbox).setOnClickListener { openInbox() }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(statusRefresher)
        handler.postDelayed(statusRefresher, STATUS_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRefresher)
    }

    private fun onNotificationsToggled(checked: Boolean) {
        ConversationCollector.setNotificationsEnabled(this, checked)
        if (checked && !ConversationCollector.hasNotificationAccess(this)) {
            if (!openNotificationAccess()) render()
            return
        }
        render()
    }

    private fun onSmsToggled(checked: Boolean) {
        if (checked) {
            smsPermissionRequest.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
        } else {
            ConversationCollector.setSmsEnabled(this, false)
            render()
        }
    }

    private fun onCallsToggled(checked: Boolean) {
        if (checked) {
            callPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    ConversationCollector.audioPermission()
                )
            )
        } else {
            ConversationCollector.setCallsEnabled(this, false)
            render()
        }
    }

    private fun render() {
        if (ConversationCollector.isNotificationsEnabled(this) && !ConversationCollector.hasNotificationAccess(this)) {
            ConversationCollector.setNotificationsEnabled(this, false)
        }
        if (ConversationCollector.isSmsEnabled(this) && !ConversationCollector.hasSms(this)) {
            ConversationCollector.setSmsEnabled(this, false)
        }
        if (ConversationCollector.isCallsEnabled(this) &&
            !ConversationCollector.hasCallLog(this) &&
            !ConversationCollector.hasAudio(this)
        ) {
            ConversationCollector.setCallsEnabled(this, false)
        }
        rendering = true
        notificationsSwitch.isChecked = ConversationCollector.isNotificationsEnabled(this)
        smsSwitch.isChecked = ConversationCollector.isSmsEnabled(this)
        callsSwitch.isChecked = ConversationCollector.isCallsEnabled(this)
        rendering = false

        val access = ConversationCollector.hasNotificationAccess(this)
        val sms = ConversationCollector.hasSms(this)
        val callLog = ConversationCollector.hasCallLog(this)
        val audio = ConversationCollector.hasAudio(this)
        val contacts = ConversationCollector.hasContacts(this)
        notificationsState.text = "알림 접근: ${label(access)}"
        notificationsState.setTextColor(stateColor(access))
        notificationAccessButton.visibility = if (access) View.GONE else View.VISIBLE
        smsState.text = "문자 읽기: ${label(sms)} · 연락처: ${label(contacts)}"
        smsState.setTextColor(stateColor(sms))
        callsState.text = "통화 기록: ${label(callLog)} · 녹음 파일: ${label(audio)} · 연락처: ${label(contacts)}"
        callsState.setTextColor(stateColor(callLog && audio))
        renderStatus()
    }

    private fun renderStatus() {
        val last = ConversationCollector.lastSyncAt(this)
        lastSyncText.text = if (last > 0) {
            "마지막 보낸 시각: " + SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(last))
        } else {
            "마지막 보낸 시각: 아직 없음"
        }
        queueText.text = "보낼 대기: ${ConversationQueue.size(this)}건"
    }

    private fun label(granted: Boolean): String = if (granted) "허용됨" else "허용 필요"

    private fun stateColor(good: Boolean): Int =
        ContextCompat.getColor(this, if (good) R.color.widget_accent else R.color.widget_rose)

    private fun syncNow() {
        if (AppConfig.widgetToken(this) == null) {
            Toast.makeText(this, "AI 대시보드 앱을 열어 로그인한 뒤 다시 시도해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        try {
            ConversationCollector.syncNow(this)
            Toast.makeText(this, "보내기를 시작했습니다", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "보내기를 시작하지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationAccess(): Boolean =
        tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun openAppInfo() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun openInbox() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(AppConfig.EXTRA_OPEN_URL, AppConfig.BASE_WEB + "/conversations")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tryStart(intent)) finish()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun showDenied() {
        Toast.makeText(
            this,
            "권한이 없어 켜지 못했습니다. ‘앱 정보 열기’ → 권한에서 허용해 주세요",
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        private const val STATUS_REFRESH_MS = 3000L
    }
}
