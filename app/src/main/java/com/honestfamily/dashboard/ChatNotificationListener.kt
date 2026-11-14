package com.honestfamily.dashboard

import android.app.Notification
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

class ChatNotificationListener : NotificationListenerService() {

    private class Draft(
        val text: String,
        val time: Long,
        val sender: String?,
        val outgoing: Boolean,
        val room: String?,
        val group: Boolean,
        val phone: String?
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = (try {
            activeNotifications
        } catch (e: Exception) {
            null
        }) ?: return
        for (sbn in active) handle(sbn)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        val channel = CHANNELS[sbn.packageName] ?: return
        val appContext = applicationContext
        if (!ConversationCollector.isNotificationsEnabled(appContext)) return
        EXECUTOR.execute {
            try {
                val messages = extract(sbn, channel)
                if (messages.isNotEmpty()) {
                    val fresh = ConversationCollector.takeUnseenNotificationIds(
                        appContext,
                        messages.map { it.optString("external_id") }
                    )
                    val queued = messages.filter { it.optString("external_id") in fresh }
                    if (queued.isNotEmpty()) {
                        ConversationQueue.add(appContext, queued)
                        ConversationCollector.scheduleQueueUpload(appContext)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun extract(sbn: StatusBarNotification, channel: String): List<JSONObject> {
        val notification = sbn.notification ?: return emptyList()
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return emptyList()
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return emptyList()
        val extras = notification.extras ?: return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val drafts = if (style != null && style.messages.isNotEmpty()) {
            fromStyle(style, title, conversationTitle, sbn.postTime)
        } else {
            fromExtras(extras, channel, title, conversationTitle, sbn.postTime)
        }
        val source = sbn.packageName
        return drafts.mapNotNull { toMessage(it, source, channel) }
    }

    private fun fromStyle(
        style: NotificationCompat.MessagingStyle,
        title: String,
        conversationTitle: String?,
        postTime: Long
    ): List<Draft> {
        val user = style.user
        val group = style.isGroupConversation
        val styleTitle = style.conversationTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: conversationTitle
        val other = style.messages.firstNotNullOfOrNull { message ->
            message.person?.takeIf { !isUser(it, user) }?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val fallbackTitle = title.ifEmpty { null }
        val room = if (group) (styleTitle ?: fallbackTitle) else (other ?: styleTitle ?: fallbackTitle)
        return style.messages.mapNotNull { message ->
            val text = message.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                val person = message.person
                val outgoing = person == null || isUser(person, user)
                Draft(
                    text = text,
                    time = if (message.timestamp > 0) message.timestamp else postTime,
                    sender = if (outgoing) null else person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    outgoing = outgoing,
                    room = room,
                    group = group,
                    phone = phoneOf(person)
                )
            }
        }
    }

    private fun fromExtras(
        extras: Bundle,
        channel: String,
        title: String,
        conversationTitle: String?,
        postTime: Long
    ): List<Draft> {
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || title.isEmpty()) return emptyList()
        val subText = if (channel == CHANNEL_KAKAO) {
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val roomName = conversationTitle ?: subText
        val group = roomName != null && roomName != title
        return listOf(
            Draft(
                text = text,
                time = postTime,
                sender = title,
                outgoing = false,
                room = if (group) roomName else title,
                group = group,
                phone = null
            )
        )
    }

    private fun toMessage(draft: Draft, source: String, channel: String): JSONObject? {
        if (MessageFilters.isPlaceholder(draft.text) || MessageFilters.isAd(draft.text)) return null
        if (channel == CHANNEL_SMS && MessageFilters.isSmsNoise(draft.text)) return null
        val room = draft.room?.takeIf { it.isNotBlank() } ?: return null
        if (room in APP_TITLES) return null
        val roomIsNumber = !draft.group && PhoneNumbers.looksLikePhone(room)
        val phone = draft.phone ?: if (roomIsNumber) PhoneNumbers.normalize(room) else null
        if (channel == CHANNEL_SMS && roomIsNumber && phone != null && !PhoneNumbers.isMobile(phone)) return null
        val sender = if (draft.outgoing) "me" else draft.sender.orEmpty()
        val externalId = "kn:" + sha1("$source|$room|$sender|${draft.time}|${draft.text}").take(32)
        return ConversationMessages.build(
            externalId = externalId,
            channel = channel,
            direction = if (draft.outgoing) "out" else "in",
            contactName = room,
            contactPhone = phone,
            body = draft.text,
            occurredAt = draft.time,
            room = room,
            senderName = if (draft.group) draft.sender else null,
            isGroup = draft.group,
            meta = JSONObject().put("app", source).put("via", "notification")
        )
    }

    private fun isUser(person: Person, user: Person): Boolean {
        val key = person.key
        val userKey = user.key
        if (key != null && userKey != null) return key == userKey
        val name = person.name?.toString()?.trim()
        return !name.isNullOrEmpty() && name == user.name?.toString()?.trim()
    }

    private fun phoneOf(person: Person?): String? {
        val uri = person?.uri ?: return null
        if (!uri.startsWith("tel:")) return null
        return PhoneNumbers.normalize(Uri.decode(uri.removePrefix("tel:"))).ifEmpty { null }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { ((it.toInt() and 0xff) or 0x100).toString(16).substring(1) }

    companion object {
        private const val CHANNEL_KAKAO = "kakao"
        private const val CHANNEL_SMS = "sms"
        private val CHANNELS = mapOf(
            "com.kakao.talk" to CHANNEL_KAKAO,
            "com.samsung.android.messaging" to CHANNEL_SMS,
            "com.google.android.apps.messaging" to CHANNEL_SMS
        )
        private val APP_TITLES = setOf("카카오톡", "KakaoTalk", "메시지", "Messages")
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
