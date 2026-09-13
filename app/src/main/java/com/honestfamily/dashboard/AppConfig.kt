package com.honestfamily.dashboard

import android.content.Context
import android.webkit.CookieManager

object AppConfig {
    const val BASE_WEB = "https://dashboard.honest-family.com"
    const val BASE_API = "https://api.dashboard.honest-family.com/api"

    private const val PREFS = "hf_dashboard"
    private const val KEY_TOKEN = "token"

    const val EXTRA_OPEN_URL = "open_url"

    fun savedToken(context: Context): String? {
        val t = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        return if (t.isNullOrBlank()) null else t
    }

    private fun saveToken(context: Context, token: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token ?: "").apply()
    }

    fun syncTokenFromCookies(context: Context) {
        val cookie = CookieManager.getInstance().getCookie(BASE_WEB) ?: return
        val token = cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")
        if (!token.isNullOrBlank()) saveToken(context, token)
    }
}
