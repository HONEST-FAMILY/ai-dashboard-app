package com.honestfamily.dashboard

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ConversationApi {
    const val UNREADABLE = -2
    private const val NETWORK_ERROR = -1
    private const val CHUNK = 64 * 1024
    private const val CONNECT_TIMEOUT = 15000
    private const val JSON_READ_TIMEOUT = 30000
    private const val MULTIPART_READ_TIMEOUT = 120000
    private const val CRLF = "\r\n"

    class Response(val code: Int, val body: String?) {
        val ok: Boolean get() = code in 200..299

        fun data(): JSONObject? = json()?.optJSONObject("data")

        fun message(): String? = json()?.optString("message", "")?.takeIf { it.isNotBlank() }

        private fun json(): JSONObject? = try {
            if (body.isNullOrBlank()) null else JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    class Attachment(val field: String, val uri: Uri, val name: String, val mime: String)

    fun postMessages(token: String, payload: JSONObject): Response =
        postJson("/conversations/phone/messages", token, payload)

    fun postStatus(token: String, payload: JSONObject): Response =
        postJson("/conversations/phone/status", token, payload)

    fun uploadFile(context: Context, token: String, fields: List<Pair<String, String>>, attachment: Attachment): Response {
        val readable = open(context, attachment.uri)?.use { true } ?: false
        if (!readable) return Response(UNREADABLE, null)
        return multipart(context, "/conversations/phone/files", token, fields, listOf(attachment))
    }

    fun share(context: Context, token: String, fields: List<Pair<String, String>>, attachments: List<Attachment>): Response =
        multipart(context, "/conversations/phone/share", token, fields, attachments)

    private fun postJson(path: String, token: String, payload: JSONObject): Response {
        var conn: HttpURLConnection? = null
        return try {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn = connect(path, token, JSON_READ_TIMEOUT)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            read(conn)
        } catch (e: Exception) {
            Response(NETWORK_ERROR, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun multipart(
        context: Context,
        path: String,
        token: String,
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            val boundary = "hf" + UUID.randomUUID().toString().replace("-", "")
            conn = connect(path, token, MULTIPART_READ_TIMEOUT)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(CHUNK)
            BufferedOutputStream(conn.outputStream, CHUNK).use { out ->
                for ((name, value) in fields) {
                    write(out, "--$boundary$CRLF")
                    write(out, "Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF")
                    write(out, value)
                    write(out, CRLF)
                }
                for (attachment in attachments) {
                    val input = open(context, attachment.uri) ?: continue
                    input.use { stream ->
                        write(out, "--$boundary$CRLF")
                        write(
                            out,
                            "Content-Disposition: form-data; name=\"${attachment.field}\"; filename=\"${headerSafe(attachment.name)}\"$CRLF"
                        )
                        write(out, "Content-Type: ${attachment.mime.ifBlank { "application/octet-stream" }}$CRLF$CRLF")
                        stream.copyTo(out, CHUNK)
                        write(out, CRLF)
                    }
                }
                write(out, "--$boundary--$CRLF")
            }
            read(conn)
        } catch (e: Exception) {
            Response(NETWORK_ERROR, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun connect(path: String, token: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(AppConfig.BASE_API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun read(conn: HttpURLConnection): Response {
        val code = conn.responseCode
        val body = try {
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        return Response(code, body)
    }

    private fun open(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        null
    }

    private fun write(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun headerSafe(name: String): String =
        name.replace("\"", "'").replace("\r", " ").replace("\n", " ")
}
