package com.honestfamily.dashboard

import android.content.Context
import org.json.JSONObject
import java.io.File

object ConversationQueue {
    private const val FILE_NAME = "conversation_queue.jsonl"
    private const val MAX_ITEMS = 5000
    private val lock = Any()
    private var cachedSize = -1

    fun add(context: Context, items: List<JSONObject>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val lines = readLines(context)
            items.mapTo(lines) { it.toString() }
            val kept = if (lines.size > MAX_ITEMS) lines.subList(lines.size - MAX_ITEMS, lines.size) else lines
            writeLines(context, kept)
        }
    }

    fun peek(context: Context, limit: Int): List<JSONObject> = synchronized(lock) {
        val result = ArrayList<JSONObject>()
        for (line in readLines(context)) {
            if (result.size >= limit) break
            parse(line)?.let { result.add(it) }
        }
        result
    }

    fun remove(context: Context, externalIds: Set<String>) {
        if (externalIds.isEmpty()) return
        synchronized(lock) {
            val kept = readLines(context).filter { line ->
                val id = parse(line)?.optString("external_id", "") ?: ""
                id.isNotEmpty() && id !in externalIds
            }
            writeLines(context, kept)
        }
    }

    fun size(context: Context): Int = synchronized(lock) {
        if (cachedSize < 0) cachedSize = readLines(context).size
        cachedSize
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun readLines(context: Context): MutableList<String> {
        val target = file(context)
        if (!target.exists()) return ArrayList()
        return try {
            target.readLines(Charsets.UTF_8).filterTo(ArrayList<String>()) { it.isNotBlank() }
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun writeLines(context: Context, lines: List<String>) {
        val target = file(context)
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
        cachedSize = lines.size
    }

    private fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (e: Exception) {
        null
    }
}
