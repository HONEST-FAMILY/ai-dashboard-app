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

    fun public(method: String, path: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BASE_API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (method == "POST") conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
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
