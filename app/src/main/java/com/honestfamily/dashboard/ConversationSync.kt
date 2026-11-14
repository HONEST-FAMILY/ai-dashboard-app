package com.honestfamily.dashboard

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class ConversationSync(private val context: Context, private val token: String) {

    private enum class Outcome { DONE, SKIP, RETRY, STOP }

    private class CallEntry(
        val id: Long,
        val phone: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val duration: Long
    )

    private class Recording(
        val id: Long,
        val fileName: String,
        val addedSec: Long,
        val modifiedSec: Long,
        val durationMs: Long,
        val size: Long,
        val mime: String,
        val time: Long,
        val party: String?
    )

    private class MmsRow(val id: Long, val date: Long, val box: Int, val type: Int)

    private class MmsImage(val partId: Long, val attachment: ConversationApi.Attachment)

    private class MmsContent(val text: String?, val images: List<MmsImage>)

    private class MmsAddresses(val from: String?, val to: List<String>)

    private val contacts = ContactNames(context)
    private var stopped = false

    fun runQueueOnly() {
        guarded { uploadQueue() }
    }

    fun runFull() {
        if (!probe()) return
        guarded { uploadQueue() }
        guarded { collectSms() }
        guarded { collectMms() }
        guarded { collectCalls() }
        guarded { uploadQueue() }
        guarded { collectRecordings() }
        guarded { sendStatus() }
    }

    private inline fun guarded(block: () -> Unit) {
        if (stopped) return
        try {
            block()
        } catch (e: Exception) {
        }
    }

    private fun probe(): Boolean {
        val result = try {
            outcome(ConversationApi.postStatus(token, ConversationCollector.serverStatus(context)))
        } catch (e: Exception) {
            Outcome.RETRY
        }
        if (result == Outcome.DONE) ConversationCollector.markSynced(context)
        if (result == Outcome.STOP) stopped = true
        return result == Outcome.DONE || result == Outcome.SKIP
    }

    private fun sendStatus() {
        val result = outcome(ConversationApi.postStatus(token, ConversationCollector.serverStatus(context)))
        if (result == Outcome.DONE) ConversationCollector.markSynced(context)
        if (result == Outcome.STOP) stopped = true
    }

    private fun outcome(response: ConversationApi.Response): Outcome = when {
        response.ok -> Outcome.DONE
        response.code == 401 -> Outcome.STOP
        response.code == ConversationApi.UNREADABLE -> Outcome.SKIP
        response.code in REJECTED_CODES -> Outcome.SKIP
        else -> Outcome.RETRY
    }

    private fun uploadQueue() {
        repeat(MAX_QUEUE_ROUNDS) {
            if (stopped) return
            val batch = ConversationQueue.peek(context, BATCH_SIZE)
            if (batch.isEmpty()) return
            val sent = when (outcome(ConversationApi.postMessages(token, messagesPayload(batch)))) {
                Outcome.DONE -> {
                    accept(batch)
                    true
                }
                Outcome.SKIP -> sendOneByOne(batch)
                Outcome.STOP -> {
                    stopped = true
                    false
                }
                Outcome.RETRY -> false
            }
            if (!sent) return
        }
    }

    private fun sendOneByOne(batch: List<JSONObject>): Boolean {
        val rejected = ArrayList<JSONObject>()
        var accepted = 0
        for (item in batch) {
            when (outcome(ConversationApi.postMessages(token, messagesPayload(listOf(item))))) {
                Outcome.DONE -> {
                    accept(listOf(item))
                    accepted++
                }
                Outcome.SKIP -> {
                    rejected.add(item)
                    if (accepted == 0 && rejected.size >= REJECT_PROBE_LIMIT) return false
                }
                Outcome.STOP -> {
                    stopped = true
                    return false
                }
                Outcome.RETRY -> return false
            }
        }
        if (accepted == 0) return false
        ConversationQueue.remove(context, externalIds(rejected))
        return true
    }

    private fun accept(items: List<JSONObject>) {
        ConversationQueue.remove(context, externalIds(items))
        ConversationCollector.markSynced(context)
    }

    private fun messagesPayload(items: List<JSONObject>): JSONObject = JSONObject()
        .put("app_version", ConversationCollector.appVersion(context))
        .put("device", Build.MODEL)
        .put("messages", JSONArray(items))

    private fun externalIds(items: List<JSONObject>): Set<String> =
        items.mapNotNullTo(HashSet<String>()) { item ->
            item.optString("external_id", "").takeIf { it.isNotEmpty() }
        }

    private fun collectSms() {
        if (!ConversationCollector.isSmsEnabled(context) || !ConversationCollector.hasSms(context)) return
        val since = ConversationCollector.smsSince(context)
        repeat(MAX_SMS_ROUNDS) {
            val cursor = ConversationCollector.smsCursor(context)
            val messages = ArrayList<JSONObject>()
            val now = System.currentTimeMillis()
            var last = cursor
            var read = 0
            var waiting = false
            context.contentResolver.query(
                SMS_URI,
                arrayOf("_id", "address", "body", "date", "type"),
                "_id > ? AND date >= ?",
                arrayOf(cursor.toString(), since.toString()),
                "_id ASC"
            )?.use { c ->
                while (read < SMS_BATCH && c.moveToNext()) {
                    read++
                    val id = c.getLong(0)
                    val date = c.getLong(3)
                    val type = c.getInt(4)
                    if ((type == SMS_OUTBOX || type == SMS_QUEUED) && date > now - PENDING_GRACE_MS) {
                        waiting = true
                        break
                    }
                    last = id
                    val direction = directionOf(type) ?: continue
                    val body = c.getString(2) ?: ""
                    if (body.isBlank() || MessageFilters.isSmsNoise(body)) continue
                    val phone = PhoneNumbers.normalize(c.getString(1))
                    val name = contacts.lookup(phone)
                    if (!PhoneNumbers.isMobile(phone) && name == null) continue
                    messages.add(
                        ConversationMessages.build(
                            externalId = "sms:$id",
                            channel = "sms",
                            direction = direction,
                            contactName = name,
                            contactPhone = phone.ifEmpty { null },
                            body = body,
                            occurredAt = date,
                            meta = JSONObject().put("via", "sms")
                        )
                    )
                }
            }
            ConversationQueue.add(context, messages)
            if (last > cursor) ConversationCollector.setSmsCursor(context, last)
            if (waiting || read < SMS_BATCH) return
        }
    }

    private fun collectMms() {
        if (!ConversationCollector.isSmsEnabled(context) || !ConversationCollector.hasSms(context)) return
        val sinceSec = ConversationCollector.smsSince(context) / 1000
        val cursor = ConversationCollector.mmsCursor(context)
        val rows = ArrayList<MmsRow>()
        context.contentResolver.query(
            MMS_URI,
            arrayOf("_id", "date", "msg_box", "m_type"),
            "_id > ? AND date >= ?",
            arrayOf(cursor.toString(), sinceSec.toString()),
            "_id ASC"
        )?.use { c ->
            while (rows.size < MMS_BATCH && c.moveToNext()) {
                rows.add(MmsRow(c.getLong(0), c.getLong(1) * 1000, c.getInt(2), c.getInt(3)))
            }
        }
        for (row in rows) {
            if (stopped) return
            when (processMms(row)) {
                Outcome.DONE, Outcome.SKIP -> ConversationCollector.setMmsCursor(context, row.id)
                Outcome.STOP -> {
                    stopped = true
                    return
                }
                Outcome.RETRY -> return
            }
        }
    }

    private fun processMms(row: MmsRow): Outcome {
        val direction = directionOf(row.box)
            ?: return if (row.box == MMS_OUTBOX && row.date > System.currentTimeMillis() - PENDING_GRACE_MS) {
                Outcome.RETRY
            } else {
                Outcome.SKIP
            }
        if (row.type == MMS_NOTIFICATION_IND) return Outcome.SKIP
        val content = mmsContent(row.id)
        val text = content.text
        if (text.isNullOrBlank() && content.images.isEmpty()) return Outcome.SKIP
        if (text != null && MessageFilters.isSmsNoise(text)) return Outcome.SKIP
        val addresses = mmsAddresses(row.id)
        val phone = PhoneNumbers.normalize(if (direction == "in") addresses.from else addresses.to.firstOrNull())
        val name = contacts.lookup(phone)
        if (!PhoneNumbers.isMobile(phone) && name == null) return Outcome.SKIP
        val meta = JSONObject().put("via", "mms")
        if (direction == "out" && addresses.to.size > 1) {
            meta.put("recipients", addresses.to.joinToString(",") { PhoneNumbers.normalize(it) })
        }
        val message = ConversationMessages.build(
            externalId = "mms:${row.id}",
            channel = "sms",
            direction = direction,
            contactName = name,
            contactPhone = phone.ifEmpty { null },
            body = text?.takeIf { it.isNotBlank() },
            occurredAt = row.date,
            meta = meta
        )
        for (image in content.images) {
            val key = "mmspart:${image.partId}"
            if (ConversationCollector.isFileUploaded(context, key)) continue
            val result = outcome(ConversationApi.uploadFile(context, token, fileFields(message, key), image.attachment))
            if (result == Outcome.STOP || result == Outcome.RETRY) return result
            ConversationCollector.markFileUploaded(context, key)
        }
        ConversationQueue.add(context, listOf(message))
        return Outcome.DONE
    }

    private fun mmsContent(mmsId: Long): MmsContent {
        val texts = ArrayList<String>()
        val images = ArrayList<MmsImage>()
        context.contentResolver.query(
            MMS_PART_URI,
            arrayOf("_id", "ct", "text", "_data", "cl", "name"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            "_id ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val type = (c.getString(1) ?: "").lowercase(Locale.ROOT)
                if (type == "text/plain") {
                    val text = c.getString(2) ?: (if (c.isNull(3)) null else readPartText(partId))
                    if (!text.isNullOrBlank()) texts.add(text.trim())
                } else if (type.startsWith("image/")) {
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
                    val fileName = c.getString(4)?.takeIf { it.isNotBlank() }
                        ?: c.getString(5)?.takeIf { it.isNotBlank() }
                        ?: "mms_$partId.$extension"
                    images.add(
                        MmsImage(
                            partId,
                            ConversationApi.Attachment("file", Uri.parse("content://mms/part/$partId"), fileName, type)
                        )
                    )
                }
            }
        }
        return MmsContent(texts.joinToString("\n").ifEmpty { null }, images)
    }

    private fun readPartText(partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private fun mmsAddresses(mmsId: Long): MmsAddresses {
        var from: String? = null
        val to = ArrayList<String>()
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                if (address.isNullOrBlank() || address == MMS_SELF_ADDRESS) continue
                val kind = c.getInt(1)
                if (kind == ADDR_FROM) from = address
                if (kind == ADDR_TO) to.add(address)
            }
        }
        return MmsAddresses(from, to)
    }

    private fun collectCalls() {
        if (!ConversationCollector.isCallsEnabled(context) || !ConversationCollector.hasCallLog(context)) return
        val cursor = ConversationCollector.callCursor(context)
        val after = maxOf(cursor, ConversationCollector.callsSince(context) - 1)
        val calls = queryCalls("${CallLog.Calls.DATE} > ?", arrayOf(after.toString()), CALL_BATCH)
        if (calls.isEmpty()) return
        val messages = ArrayList<JSONObject>()
        for (call in calls) {
            val direction = callDirection(call.type) ?: continue
            val missed = call.type == CallLog.Calls.MISSED_TYPE
            val meta = JSONObject().put("via", "call_log")
            if (missed) meta.put("call_type", "missed")
            messages.add(
                ConversationMessages.build(
                    externalId = "call:${call.id}",
                    channel = "call",
                    direction = direction,
                    contactName = call.name ?: contacts.lookup(call.phone),
                    contactPhone = call.phone.ifEmpty { null },
                    body = if (missed) "부재중 전화" else null,
                    occurredAt = call.date,
                    durationSec = call.duration,
                    meta = meta
                )
            )
        }
        ConversationQueue.add(context, messages)
        ConversationCollector.setCallCursor(context, maxOf(cursor, calls.maxOf { it.date }))
    }

    private fun queryCalls(selection: String, args: Array<String>, limit: Int): List<CallEntry> {
        val calls = ArrayList<CallEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            selection,
            args,
            "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            while (calls.size < limit && c.moveToNext()) {
                val phone = PhoneNumbers.normalize(c.getString(1))
                calls.add(
                    CallEntry(
                        id = c.getLong(0),
                        phone = if (phone.length >= 3) phone else "",
                        name = c.getString(2)?.takeIf { it.isNotBlank() },
                        type = c.getInt(3),
                        date = c.getLong(4),
                        duration = c.getLong(5)
                    )
                )
            }
        }
        return calls
    }

    private fun collectRecordings() {
        if (!ConversationCollector.isCallsEnabled(context) || !ConversationCollector.hasAudio(context)) return
        val sinceMs = ConversationCollector.callsSince(context)
        val cursor = ConversationCollector.recordingCursor(context)
        val recordings = queryRecordings(maxOf(cursor, sinceMs / 1000 - 1))
        if (recordings.isEmpty()) return
        val calls = if (ConversationCollector.hasCallLog(context)) {
            val from = recordings.minOf { it.time } - MATCH_WINDOW_MS
            val to = recordings.maxOf { it.time } + MATCH_WINDOW_MS
            queryCalls(
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf(from.toString(), to.toString()),
                MATCH_CALL_LIMIT
            ).filter { it.type == CallLog.Calls.INCOMING_TYPE || it.type == CallLog.Calls.OUTGOING_TYPE }
        } else {
            emptyList()
        }
        val settledBefore = System.currentTimeMillis() / 1000 - SETTLE_SEC
        for (recording in recordings) {
            if (stopped) return
            if (recording.modifiedSec > settledBefore) return
            val result = if (recording.size <= 0 || recording.size > MAX_FILE_BYTES || recording.time < sinceMs) {
                Outcome.SKIP
            } else {
                uploadRecording(recording, calls)
            }
            when (result) {
                Outcome.DONE, Outcome.SKIP -> ConversationCollector.setRecordingCursor(context, recording.addedSec)
                Outcome.STOP -> {
                    stopped = true
                    return
                }
                Outcome.RETRY -> return
            }
        }
    }

    private fun queryRecordings(afterSec: Long): List<Recording> {
        val pathSelection: String
        val pathArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pathSelection = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
            pathArgs = arrayOf("Recordings/Call/%", "Call/%")
        } else {
            pathSelection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            pathArgs = arrayOf("%/Call/%")
        }
        val recordings = ArrayList<Recording>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.Audio.AudioColumns.DURATION,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            ),
            "$pathSelection AND ${MediaStore.MediaColumns.DATE_ADDED} > ?",
            pathArgs + afterSec.toString(),
            "${MediaStore.MediaColumns.DATE_ADDED} ASC"
        )?.use { c ->
            while (recordings.size < RECORDING_BATCH && c.moveToNext()) {
                val id = c.getLong(0)
                val fileName = c.getString(1)?.takeIf { it.isNotBlank() } ?: "call_$id.m4a"
                val addedSec = c.getLong(2)
                val durationMs = c.getLong(4)
                val parsed = CallRecordingNames.parse(fileName)
                recordings.add(
                    Recording(
                        id = id,
                        fileName = fileName,
                        addedSec = addedSec,
                        modifiedSec = c.getLong(3),
                        durationMs = durationMs,
                        size = c.getLong(5),
                        mime = c.getString(6)?.takeIf { it.isNotBlank() } ?: "audio/mp4",
                        time = parsed?.time ?: (addedSec * 1000 - durationMs),
                        party = parsed?.party
                    )
                )
            }
        }
        return recordings
    }

    private fun uploadRecording(recording: Recording, calls: List<CallEntry>): Outcome {
        val key = "rec:${recording.id}"
        if (ConversationCollector.isFileUploaded(context, key)) return Outcome.DONE
        val partyPhone = recording.party?.takeIf { PhoneNumbers.looksLikePhone(it) }?.let { PhoneNumbers.normalize(it) }
        val partyName = if (partyPhone == null) recording.party else null
        val call = matchCall(recording, calls, partyPhone, partyName)
        val message = if (call != null) {
            ConversationMessages.build(
                externalId = "call:${call.id}",
                channel = "call",
                direction = if (call.type == CallLog.Calls.OUTGOING_TYPE) "out" else "in",
                contactName = call.name ?: partyName ?: contacts.lookup(call.phone),
                contactPhone = call.phone.ifEmpty { partyPhone },
                body = null,
                occurredAt = call.date,
                durationSec = call.duration
            )
        } else {
            ConversationMessages.build(
                externalId = key,
                channel = "call",
                direction = "in",
                contactName = partyName ?: contacts.lookup(partyPhone),
                contactPhone = partyPhone,
                body = null,
                occurredAt = recording.time,
                durationSec = recording.durationMs / 1000
            )
        }
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, recording.id)
        val attachment = ConversationApi.Attachment("file", uri, recording.fileName, recording.mime)
        val result = outcome(ConversationApi.uploadFile(context, token, fileFields(message, key), attachment))
        if (result == Outcome.DONE || result == Outcome.SKIP) ConversationCollector.markFileUploaded(context, key)
        return result
    }

    private fun matchCall(recording: Recording, calls: List<CallEntry>, phone: String?, name: String?): CallEntry? {
        val near = calls.filter { abs(it.date - recording.time) <= MATCH_WINDOW_MS }
        if (near.isEmpty()) return null
        val same = near.filter { (phone != null && it.phone == phone) || (name != null && it.name == name) }
        return same.ifEmpty { near }.minByOrNull { abs(it.date - recording.time) }
    }

    private fun fileFields(message: JSONObject, fileKey: String): List<Pair<String, String>> {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("external_id" to message.optString("external_id"))
        fields.add("file_key" to fileKey)
        for (name in FILE_MESSAGE_FIELDS) {
            if (!message.isNull(name)) fields.add(name to message.get(name).toString())
        }
        return fields
    }

    private fun directionOf(type: Int): String? = when (type) {
        1 -> "in"
        2 -> "out"
        else -> null
    }

    private fun callDirection(type: Int): String? = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.MISSED_TYPE -> "in"
        CallLog.Calls.OUTGOING_TYPE -> "out"
        else -> null
    }

    companion object {
        private val SMS_URI: Uri = Uri.parse("content://sms")
        private val MMS_URI: Uri = Uri.parse("content://mms")
        private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")
        private val REJECTED_CODES = setOf(400, 403, 409, 413, 415, 422)
        private val FILE_MESSAGE_FIELDS = listOf(
            "channel", "direction", "contact_name", "contact_phone", "occurred_at", "duration_sec", "body"
        )
        private const val BATCH_SIZE = 200
        private const val MAX_QUEUE_ROUNDS = 30
        private const val REJECT_PROBE_LIMIT = 20
        private const val SMS_BATCH = 500
        private const val MAX_SMS_ROUNDS = 4
        private const val MMS_BATCH = 100
        private const val CALL_BATCH = 500
        private const val MATCH_CALL_LIMIT = 2000
        private const val RECORDING_BATCH = 20
        private const val SMS_OUTBOX = 4
        private const val SMS_QUEUED = 6
        private const val MMS_OUTBOX = 4
        private const val MMS_NOTIFICATION_IND = 130
        private const val MMS_SELF_ADDRESS = "insert-address-token"
        private const val ADDR_FROM = 137
        private const val ADDR_TO = 151
        private const val PENDING_GRACE_MS = 10L * 60 * 1000
        private const val MATCH_WINDOW_MS = 3L * 60 * 1000
        private const val SETTLE_SEC = 60L
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}
