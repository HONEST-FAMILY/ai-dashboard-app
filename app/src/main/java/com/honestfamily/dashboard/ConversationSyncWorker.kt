package com.honestfamily.dashboard

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters

class ConversationSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val appContext = applicationContext
        val token = AppConfig.widgetToken(appContext) ?: return Result.success()
        synchronized(RUN_LOCK) {
            val sync = ConversationSync(appContext, token)
            if (inputData.getString(KEY_MODE) == MODE_QUEUE) sync.runQueueOnly() else sync.runFull()
        }
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_QUEUE = "queue"
        private val RUN_LOCK = Any()
    }
}
