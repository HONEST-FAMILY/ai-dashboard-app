package com.honestfamily.dashboard

import org.json.JSONObject

object ConversationMessages {
    private const val MAX_TEXT_FIELD = 190
    private const val MAX_PHONE_FIELD = 40

    fun build(
        externalId: String,
        channel: String,
        direction: String,
        contactName: String?,
        contactPhone: String?,
        body: String?,
        occurredAt: Long,
        room: String? = null,
        senderName: String? = null,
        isGroup: Boolean = false,
        durationSec: Long? = null,
        meta: JSONObject = JSONObject()
    ): JSONObject = JSONObject()
        .put("external_id", externalId.take(MAX_TEXT_FIELD))
        .put("channel", channel)
        .put("direction", direction)
        .put("contact_name", clean(contactName, MAX_TEXT_FIELD) ?: JSONObject.NULL)
        .put("contact_phone", clean(contactPhone, MAX_PHONE_FIELD) ?: JSONObject.NULL)
        .put("room", clean(room, MAX_TEXT_FIELD) ?: JSONObject.NULL)
        .put("sender_name", clean(senderName, MAX_TEXT_FIELD) ?: JSONObject.NULL)
        .put("is_group", isGroup)
        .put("body", body ?: JSONObject.NULL)
        .put("occurred_at", occurredAt)
        .put("duration_sec", durationSec ?: JSONObject.NULL)
        .put("meta", meta)

    private fun clean(value: String?, max: Int): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
}
