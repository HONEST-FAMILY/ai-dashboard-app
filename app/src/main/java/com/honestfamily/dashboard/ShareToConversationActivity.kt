package com.honestfamily.dashboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class ShareToConversationActivity : AppCompatActivity() {

    private class SharedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

    private lateinit var nameInput: EditText
    private lateinit var channelGroup: RadioGroup
    private lateinit var submitButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressRow: View
    private var sharedText: String? = null
    private var files: List<SharedFile> = emptyList()
    private var sourceApp: String? = null
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_to_conversation)
        nameInput = findViewById(R.id.input_contact_name)
        channelGroup = findViewById(R.id.group_channel)
        submitButton = findViewById(R.id.button_share_submit)
        cancelButton = findViewById(R.id.button_share_cancel)
        progressRow = findViewById(R.id.layout_share_progress)

        sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        files = streamUris(intent).map { describe(it) }
        if (sharedText == null && files.isEmpty()) {
            Toast.makeText(this, "넣을 내용이 없습니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceApp = referrer?.takeIf { it.scheme == "android-app" }?.host

        val kakaoName = kakaoNameFromText() ?: kakaoNameFromFiles()
        if (kakaoName != null) {
            nameInput.setText(kakaoName)
            nameInput.setSelection(kakaoName.length)
        }
        channelGroup.check(radioFor(defaultChannel(kakaoName != null || looksLikeKakaoExport())))
        findViewById<TextView>(R.id.text_share_summary).text = summary()
        renderRecentNames()

        submitButton.setOnClickListener { submit() }
        cancelButton.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!sending) finish()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        try {
            if (intent.action == Intent.ACTION_SEND) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        } catch (e: Exception) {
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        return uris.toList()
    }

    private fun describe(uri: Uri): SharedFile {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
        }
        val fileName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared_file"
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = (try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return SharedFile(uri, fileName, mime, size)
    }

    private fun summary(): String {
        val parts = ArrayList<String>()
        if (sharedText != null) parts.add("글 1건")
        if (files.size == 1) parts.add("파일 1개 (${files[0].name})")
        if (files.size > 1) parts.add("파일 ${files.size}개")
        return parts.joinToString(" · ")
    }

    private fun kakaoNameFromText(): String? {
        val text = sharedText
        if (text != null) kakaoNameFromHeader(firstLine(text))?.let { return it }
        return kakaoNameFromHeader(intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    private fun kakaoNameFromFiles(): String? {
        for (file in files) {
            val match = KAKAO_FILE.find(file.name.substringBeforeLast('.')) ?: continue
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        for (file in files) {
            if (!isText(file)) continue
            kakaoNameFromHeader(readFirstLine(file.uri))?.let { return it }
        }
        return null
    }

    private fun kakaoNameFromHeader(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val match = KAKAO_HEADER.find(line) ?: KAKAO_HEADER_EN.find(line) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun looksLikeKakaoExport(): Boolean = files.any { KAKAO_EXPORT_FILE.containsMatchIn(it.name) }

    private fun firstLine(text: String): String? =
        text.removePrefix("﻿").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private fun readFirstLine(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(4096)
            val read = input.read(buffer)
            if (read > 0) firstLine(String(buffer, 0, read, Charsets.UTF_8)) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun isText(file: SharedFile): Boolean =
        file.mime.startsWith("text/") || file.name.endsWith(".txt", ignoreCase = true)

    private fun defaultChannel(kakao: Boolean): String {
        val source = sourceApp
        return when {
            kakao || source == KAKAO_PACKAGE -> "kakao"
            source != null && source in SMS_PACKAGES -> "sms"
            source != null && source in EMAIL_PACKAGES -> "email"
            else -> "etc"
        }
    }

    private fun radioFor(channel: String): Int = when (channel) {
        "kakao" -> R.id.radio_channel_kakao
        "sms" -> R.id.radio_channel_sms
        "email" -> R.id.radio_channel_email
        "call" -> R.id.radio_channel_call
        else -> R.id.radio_channel_etc
    }

    private fun channelFor(radioId: Int): String = when (radioId) {
        R.id.radio_channel_kakao -> "kakao"
        R.id.radio_channel_sms -> "sms"
        R.id.radio_channel_email -> "email"
        R.id.radio_channel_call -> "call"
        else -> "etc"
    }

    private fun renderRecentNames() {
        val names = ConversationCollector.recentShareNames(this)
        val scroller = findViewById<View>(R.id.scroll_recent_names)
        val row = findViewById<LinearLayout>(R.id.layout_recent_names)
        row.removeAllViews()
        if (names.isEmpty()) {
            scroller.visibility = View.GONE
            return
        }
        scroller.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val horizontal = (12 * density).toInt()
        val vertical = (6 * density).toInt()
        for (recent in names) {
            val chip = TextView(this)
            chip.text = recent
            chip.textSize = 14f
            chip.setTextColor(ContextCompat.getColor(this, R.color.collector_text))
            chip.setBackgroundResource(R.drawable.bg_name_chip)
            chip.setPadding(horizontal, vertical, horizontal, vertical)
            chip.setOnClickListener {
                nameInput.setText(recent)
                nameInput.setSelection(recent.length)
                nameInput.error = null
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = (8 * density).toInt()
            row.addView(chip, params)
        }
    }

    private fun submit() {
        if (sending) return
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "이름을 입력해 주세요"
            nameInput.requestFocus()
            return
        }
        val token = AppConfig.widgetToken(this)
        if (token == null) {
            Toast.makeText(this, "AI 대시보드 앱을 열어 로그인한 뒤 다시 공유해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val accepted = files.filter { it.size <= MAX_FILE_BYTES }.take(MAX_FILES)
        val left = files.size - accepted.size
        val text = sharedText
        if (text == null && accepted.isEmpty()) {
            Toast.makeText(this, "파일이 너무 커서 넣을 수 없습니다 (50MB까지)", Toast.LENGTH_LONG).show()
            return
        }
        val fields = ArrayList<Pair<String, String>>()
        fields.add("contact_name" to name)
        fields.add("channel" to channelFor(channelGroup.checkedRadioButtonId))
        if (text != null) fields.add("text" to text)
        sourceApp?.let { fields.add("source_app" to it) }
        val attachments = accepted.map { ConversationApi.Attachment("files[]", it.uri, it.name, it.mime) }
        setSending(true)
        val appContext = applicationContext
        Thread {
            val response = ConversationApi.share(appContext, token, fields, attachments)
            runOnUiThread { onShared(response, name, left) }
        }.start()
    }

    private fun onShared(response: ConversationApi.Response, name: String, left: Int) {
        if (response.ok) {
            ConversationCollector.rememberShareName(this, name)
            val data = response.data()
            val messages = data?.optInt("stored_messages", 0) ?: 0
            val stored = data?.optInt("stored_files", 0) ?: 0
            val note = if (left > 0) "\n빠진 파일 ${left}개 (50MB 넘음·20개 초과)" else ""
            Toast.makeText(
                applicationContext,
                "대화 수집함에 넣었습니다 (메시지 $messages · 파일 $stored)$note",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        setSending(false)
        Toast.makeText(applicationContext, failureMessage(response), Toast.LENGTH_LONG).show()
    }

    private fun failureMessage(response: ConversationApi.Response): String = when (response.code) {
        -1 -> "보내지 못했습니다 — 인터넷 연결을 확인해 주세요"
        401 -> "로그인이 필요합니다 — AI 대시보드 앱을 열어 로그인한 뒤 다시 공유해 주세요"
        413 -> "파일이 너무 커서 보내지 못했습니다 (50MB까지)"
        500 -> "보내지 못했습니다 — 로그인이 만료됐을 수 있습니다. AI 대시보드 앱을 열어 로그인한 뒤 다시 공유해 주세요"
        else -> "보내지 못했습니다 (오류 ${response.code})" + (response.message()?.let { " — $it" } ?: "")
    }

    private fun setSending(on: Boolean) {
        sending = on
        progressRow.visibility = if (on) View.VISIBLE else View.GONE
        submitButton.isEnabled = !on
        cancelButton.isEnabled = !on
        nameInput.isEnabled = !on
        for (i in 0 until channelGroup.childCount) channelGroup.getChildAt(i).isEnabled = !on
    }

    companion object {
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        private const val MAX_FILES = 20
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private val SMS_PACKAGES = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging")
        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.samsung.android.email.provider",
            "com.nhn.android.mail",
            "net.daum.android.mail"
        )
        private val KAKAO_HEADER = Regex("^(.+?)\\s*님과\\s*카카오톡\\s*대화")
        private val KAKAO_HEADER_EN = Regex("^KakaoTalk\\s+Chats?\\s+with\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val KAKAO_FILE = Regex("^KakaoTalk_Chat_(.+?)(?:_[0-9][0-9._ -]*)?$")
        private val KAKAO_EXPORT_FILE = Regex("^(KakaoTalk|Talk_)", RegexOption.IGNORE_CASE)
    }
}
