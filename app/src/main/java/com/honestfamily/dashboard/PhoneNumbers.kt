package com.honestfamily.dashboard

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object PhoneNumbers {
    private val MOBILE = Regex("^01[0-9]{8,9}$")
    private val PHONE_LIKE = Regex("^\\+?[0-9][0-9 ().-]{5,}$")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        var digits = trimmed.filter { it in '0'..'9' }
        if (trimmed.startsWith("+82") || digits.startsWith("0082")) {
            digits = digits.removePrefix("00").removePrefix("82")
            if (!digits.startsWith("0")) digits = "0$digits"
        }
        return digits
    }

    fun isMobile(number: String): Boolean = MOBILE.matches(number)

    fun looksLikePhone(text: String?): Boolean = text != null && PHONE_LIKE.matches(text.trim())
}

class ContactNames(private val context: Context) {
    private val allowed = ConversationCollector.hasContacts(context)
    private val cache = HashMap<String, String?>()

    fun lookup(number: String?): String? {
        if (!allowed || number.isNullOrEmpty()) return null
        if (cache.containsKey(number)) return cache[number]
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            null
        }
        cache[number] = name
        return name
    }
}
