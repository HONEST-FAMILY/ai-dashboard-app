package com.honestfamily.dashboard

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ConversationCollector {
    private const val PREFS = "hf_conversation_collector"
    private const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val KEY_SMS = "sms_enabled"
    private const val KEY_CALLS = "calls_enabled"
    private const val KEY_SMS_SINCE = "sms_since"
    private const val KEY_CALLS_SINCE = "calls_since"
    private const val KEY_SMS_CURSOR = "sms_cursor"
    private const val KEY_MMS_CURSOR = "mms_cursor"
    private const val KEY_CALL_CURSOR = "call_cursor"
    private const val KEY_RECORDING_CURSOR = "recording_cursor"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_SEEN_NOTIFICATIONS = "seen_notification_ids"
    private const val KEY_UPLOADED_FILES = "uploaded_file_keys"
    private const val KEY_SHARE_NAMES = "share_recent_names"

    private const val BACKFILL_MS = 3L * 24 * 60 * 60 * 1000
    private const val SEEN_LIMIT = 500
    private const val UPLOADED_LIMIT = 500
    private const val SHARE_NAMES_LIMIT = 10

    private const val PERIODIC_WORK = "conversation_sync_periodic"
    private const val NOW_WORK = "conversation_sync_now"
    private const val QUEUE_WORK = "conversation_queue_upload"

    private val lock = Any()
    private var seenNotifications: LinkedHashSet<String>? = null
    private var uploadedFiles: LinkedHashSet<String>? = null

    fun isNotificationsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFICATIONS, false)

    fun isSmsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SMS, false)

    fun isCallsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CALLS, false)

    fun anyEnabled(context: Context): Boolean =
        isNotificationsEnabled(context) || isSmsEnabled(context) || isCallsEnabled(context)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_NOTIFICATIONS, null, enabled)

    fun setSmsEnabled(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_SMS, KEY_SMS_SINCE, enabled)

    fun setCallsEnabled(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_CALLS, KEY_CALLS_SINCE, enabled)

    private fun setEnabled(context: Context, key: String, sinceKey: String?, enabled: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(key, false) == enabled) return
        val editor = prefs.edit().putBoolean(key, enabled)
        if (enabled && sinceKey != null) editor.putLong(sinceKey, System.currentTimeMillis() - BACKFILL_MS)
        editor.apply()
        try {
            if (anyEnabled(context)) {
                schedulePeriodic(context)
                syncNow(context)
            } else {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
            }
        } catch (e: Exception) {
        }
    }

    fun smsSince(context: Context): Long =
        prefs(context).getLong(KEY_SMS_SINCE, System.currentTimeMillis() - BACKFILL_MS)

    fun callsSince(context: Context): Long =
        prefs(context).getLong(KEY_CALLS_SINCE, System.currentTimeMillis() - BACKFILL_MS)

    fun smsCursor(context: Context): Long = prefs(context).getLong(KEY_SMS_CURSOR, 0L)

    fun setSmsCursor(context: Context, value: Long) = putLong(context, KEY_SMS_CURSOR, value)

    fun mmsCursor(context: Context): Long = prefs(context).getLong(KEY_MMS_CURSOR, 0L)

    fun setMmsCursor(context: Context, value: Long) = putLong(context, KEY_MMS_CURSOR, value)

    fun callCursor(context: Context): Long = prefs(context).getLong(KEY_CALL_CURSOR, 0L)

    fun setCallCursor(context: Context, value: Long) = putLong(context, KEY_CALL_CURSOR, value)

    fun recordingCursor(context: Context): Long = prefs(context).getLong(KEY_RECORDING_CURSOR, 0L)

    fun setRecordingCursor(context: Context, value: Long) = putLong(context, KEY_RECORDING_CURSOR, value)

    fun lastSyncAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun markSynced(context: Context) = putLong(context, KEY_LAST_SYNC, System.currentTimeMillis())

    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun hasSms(context: Context): Boolean = granted(context, Manifest.permission.READ_SMS)

    fun hasCallLog(context: Context): Boolean = granted(context, Manifest.permission.READ_CALL_LOG)

    fun hasContacts(context: Context): Boolean = granted(context, Manifest.permission.READ_CONTACTS)

    fun hasAudio(context: Context): Boolean = granted(context, audioPermission())

    fun takeUnseenNotificationIds(context: Context, ids: Collection<String>): Set<String> = synchronized(lock) {
        val seen = seenNotifications ?: loadSet(context, KEY_SEEN_NOTIFICATIONS).also { seenNotifications = it }
        val fresh = ids.filterTo(LinkedHashSet<String>()) { it.isNotEmpty() && it !in seen }
        if (fresh.isNotEmpty()) {
            seen.addAll(fresh)
            trim(seen, SEEN_LIMIT)
            saveSet(context, KEY_SEEN_NOTIFICATIONS, seen)
        }
        fresh
    }

    fun isFileUploaded(context: Context, key: String): Boolean = synchronized(lock) {
        uploadedSet(context).contains(key)
    }

    fun markFileUploaded(context: Context, key: String) {
        synchronized(lock) {
            val uploaded = uploadedSet(context)
            uploaded.add(key)
            trim(uploaded, UPLOADED_LIMIT)
            saveSet(context, KEY_UPLOADED_FILES, uploaded)
        }
    }

    fun recentShareNames(context: Context): List<String> =
        (prefs(context).getString(KEY_SHARE_NAMES, null) ?: "").split('\n').filter { it.isNotBlank() }

    fun rememberShareName(context: Context, name: String) {
        val clean = name.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        val names = listOf(clean) + recentShareNames(context).filter { it != clean }
        prefs(context).edit().putString(KEY_SHARE_NAMES, names.take(SHARE_NAMES_LIMIT).joinToString("\n")).apply()
    }

    fun bridgeStatus(context: Context): JSONObject = JSONObject()
        .put("version", appVersion(context))
        .put("permissions", permissionsJson(context))
        .put("enabled", enabledJson(context))
        .put("queue", ConversationQueue.size(context))
        .put("last_sync_at", lastSyncJson(context))

    fun serverStatus(context: Context): JSONObject = JSONObject()
        .put("app_version", appVersion(context))
        .put("device", Build.MODEL)
        .put("permissions", permissionsJson(context))
        .put("enabled", enabledJson(context))
        .put("queue", ConversationQueue.size(context))
        .put("last_sync_at", lastSyncJson(context))

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    fun ensureScheduled(context: Context) {
        if (!anyEnabled(context)) return
        try {
            schedulePeriodic(context)
        } catch (e: Exception) {
        }
    }

    fun syncOnResume(context: Context) {
        if (!anyEnabled(context)) return
        try {
            syncNow(context)
        } catch (e: Exception) {
        }
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConversationSyncWorker>()
            .setConstraints(connected())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleQueueUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConversationSyncWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setConstraints(connected())
            .setInputData(workDataOf(ConversationSyncWorker.KEY_MODE to ConversationSyncWorker.MODE_QUEUE))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ConversationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun connected(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun permissionsJson(context: Context): JSONObject = JSONObject()
        .put("notification_listener", hasNotificationAccess(context))
        .put("sms", hasSms(context))
        .put("call_log", hasCallLog(context))
        .put("audio", hasAudio(context))
        .put("contacts", hasContacts(context))

    private fun enabledJson(context: Context): JSONObject = JSONObject()
        .put("kakao", isNotificationsEnabled(context))
        .put("sms", isSmsEnabled(context))
        .put("calls", isCallsEnabled(context))

    private fun lastSyncJson(context: Context): Any {
        val last = lastSyncAt(context)
        return if (last > 0) last else JSONObject.NULL
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun uploadedSet(context: Context): LinkedHashSet<String> =
        uploadedFiles ?: loadSet(context, KEY_UPLOADED_FILES).also { uploadedFiles = it }

    private fun loadSet(context: Context, key: String): LinkedHashSet<String> =
        (prefs(context).getString(key, null) ?: "").split('\n').filterTo(LinkedHashSet<String>()) { it.isNotBlank() }

    private fun saveSet(context: Context, key: String, values: Collection<String>) {
        prefs(context).edit().putString(key, values.joinToString("\n")).apply()
    }

    private fun trim(values: LinkedHashSet<String>, limit: Int) {
        val iterator = values.iterator()
        while (values.size > limit && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun putLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
