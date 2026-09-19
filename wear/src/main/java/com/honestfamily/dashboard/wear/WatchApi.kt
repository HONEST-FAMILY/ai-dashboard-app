package com.honestfamily.dashboard.wear

import java.net.HttpURLConnection
import java.net.URL

object WatchApi {
    const val BASE_API = "https://api.dashboard.honest-family.com/api"

    class Result(val code: Int, val body: String?)

    fun request(path: String, token: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BASE_API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            Result(code, body)
        } catch (e: Exception) {
            Result(-1, null)
        } finally {
            conn?.disconnect()
        }
    }
}
