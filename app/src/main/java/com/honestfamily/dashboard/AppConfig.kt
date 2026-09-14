package com.honestfamily.dashboard

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject

object AppConfig {
    const val BASE_WEB = "https://dashboard.honest-family.com"
    const val BASE_API = "https://api.dashboard.honest-family.com/api"

    private const val PREFS = "hf_dashboard"
    private const val KEY_TOKEN = "token"
    private const val KEY_WIDGET_TOKEN = "widget_token"

    const val EXTRA_OPEN_URL = "open_url"

    fun savedToken(context: Context): String? {
        val t = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        return if (t.isNullOrBlank()) null else t
    }

    // 위젯·알림은 장수명 토큰을 쓴다. 없으면 세션 토큰으로 폴백한다.
    fun widgetToken(context: Context): String? {
        val w = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WIDGET_TOKEN, null)
        return if (!w.isNullOrBlank()) w else savedToken(context)
    }

    private fun saveToken(context: Context, token: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token ?: "").apply()
    }

    private fun saveWidgetToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WIDGET_TOKEN, token).apply()
    }

    fun syncTokenFromCookies(context: Context) {
        val cookie = CookieManager.getInstance().getCookie(BASE_WEB) ?: return
        val token = cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")
        if (!token.isNullOrBlank()) saveToken(context, token)
    }

    fun syncWidgetToken(context: Context) {
        val session = savedToken(context) ?: return
        val body = Api.get("/auth/widget-token", session) ?: return
        try {
            val token = JSONObject(body).optJSONObject("data")?.optString("token", "")
            if (!token.isNullOrBlank()) saveWidgetToken(context, token)
        } catch (e: Exception) {
        }
    }
}
