package com.honestfamily.dashboard

import java.net.HttpURLConnection
import java.net.URL

object Api {
    class Result(val code: Int, val body: String?)

    fun request(path: String, token: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AppConfig.BASE_API + path).openConnection() as HttpURLConnection).apply {
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

    fun get(path: String, token: String): String? = request(path, token).body

    fun put(path: String, token: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AppConfig.BASE_API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
