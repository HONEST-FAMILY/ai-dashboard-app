package com.honestfamily.dashboard.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchActivity : Activity() {

    private lateinit var view: FocusView
    private val handler = Handler(Looper.getMainLooper())
    private var loadedOnce = false

    private val ticker = object : Runnable {
        override fun run() {
            view.tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = FocusView(this)
        view.onRefresh = { refresh(false) }
        view.isFocusable = true
        view.requestFocus()
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        refresh(!loadedOnce)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refresh(showLoading: Boolean) {
        if (showLoading) view.setStatus(FocusView.Status.LOADING)
        Thread {
            readSyncedToken()
            val token = WatchStore.token(applicationContext)
            if (token == null) {
                runOnUiThread { view.setStatus(FocusView.Status.NEED_TOKEN) }
                return@Thread
            }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val res = WatchApi.request("/schedules?from=$today&to=$today&mine=1&is_done=0", token)
            when {
                res.code == 200 && res.body != null -> {
                    val parsed = parse(res.body)
                    loadedOnce = true
                    runOnUiThread { view.setData(parsed.first, parsed.second) }
                }
                res.code == 401 -> runOnUiThread { view.setStatus(FocusView.Status.NEED_TOKEN) }
                else -> runOnUiThread {
                    if (!loadedOnce) view.setStatus(FocusView.Status.ERROR)
                }
            }
        }.start()
    }

    private fun readSyncedToken() {
        try {
            val buffer = Tasks.await(Wearable.getDataClient(applicationContext).dataItems)
            try {
                for (di in buffer) {
                    if (di.uri.path == TokenListenerService.PATH_TOKEN) {
                        val t = DataMapItem.fromDataItem(di).dataMap.getString("token")
                        if (!t.isNullOrBlank()) WatchStore.saveToken(applicationContext, t)
                    }
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
        }
    }

    private fun parse(body: String): Pair<List<FocusView.Item>, List<String>> {
        return try {
            val arr = JSONObject(body).optJSONArray("data")
                ?: return Pair(emptyList(), emptyList())
            val timed = ArrayList<FocusView.Item>()
            val allDay = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("is_done", false)) continue
                val title = o.optString("title", "(제목 없음)")
                val color = o.optInt("color", 1)
                val start = if (o.isNull("start_minute")) null else o.optInt("start_minute")
                val end = if (o.isNull("end_minute")) null else o.optInt("end_minute")
                val projectRaw = o.optString("project_name", "")
                val project = if (projectRaw.isBlank()) null else projectRaw
                if (start != null) {
                    timed.add(FocusView.Item(title, color, start, end, project))
                } else {
                    allDay.add(title)
                }
            }
            timed.sortBy { it.start }
            Pair(timed, allDay)
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }
}
