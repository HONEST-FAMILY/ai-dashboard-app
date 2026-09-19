package com.honestfamily.dashboard.wear

import android.content.Context

object WatchStore {
    private const val PREFS = "hf_wear"
    private const val KEY_TOKEN = "token"

    fun token(context: Context): String? {
        val t = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        return if (t.isNullOrBlank()) null else t
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }
}
