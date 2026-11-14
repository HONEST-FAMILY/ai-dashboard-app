package com.honestfamily.dashboard

import java.text.SimpleDateFormat
import java.util.Locale

object CallRecordingNames {
    class Parsed(val party: String?, val time: Long)

    private val STAMP = Regex("^(.*)_([0-9]{6})_([0-9]{6})")
    private val PREFIXES = listOf("통화 녹음", "통화녹음", "Call recording", "Call_recording")

    fun parse(fileName: String): Parsed? {
        val base = fileName.substringBeforeLast('.')
        val match = STAMP.find(base) ?: return null
        val time = (try {
            SimpleDateFormat("yyMMddHHmmss", Locale.US).apply { isLenient = false }
                .parse(match.groupValues[2] + match.groupValues[3])?.time
        } catch (e: Exception) {
            null
        }) ?: return null
        var party = match.groupValues[1].trim()
        for (prefix in PREFIXES) {
            if (party.startsWith(prefix, ignoreCase = true)) {
                party = party.substring(prefix.length).trim()
                break
            }
        }
        return Parsed(party.ifEmpty { null }, time)
    }
}
