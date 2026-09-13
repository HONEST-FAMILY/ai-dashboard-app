package com.honestfamily.dashboard

import java.net.HttpURLConnection
import java.net.URL

object Api {
    fun get(path: String, token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AppConfig.BASE_API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
