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
    private var pairing = false
    private var pairCode: String? = null

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
        if (pairing && pairCode != null) {
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, 1000)
        } else {
            refresh(!loadedOnce)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(pollRunnable)
    }

    private fun refresh(showLoading: Boolean) {
        if (showLoading && !pairing) view.setStatus(FocusView.Status.LOADING)
        Thread {
            readSyncedToken()
            val token = WatchStore.token(applicationContext)
            if (token == null) {
                startPairing()
                return@Thread
            }
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val res = WatchApi.request("/schedules?from=$today&to=$today&mine=1&is_done=0", token)
            when {
                res.code == 200 && res.body != null -> {
                    val parsed = parse(res.body)
                    loadedOnce = true
                    pairing = false
                    pairCode = null
                    handler.removeCallbacks(pollRunnable)
                    runOnUiThread { view.setData(parsed.first, parsed.second) }
                }
                res.code == 401 -> startPairing()
                else -> runOnUiThread {
                    if (!loadedOnce) view.setStatus(FocusView.Status.ERROR)
                }
            }
        }.start()
    }

    private fun startPairing() {
        if (pairing) return
        pairing = true
        Thread {
            val res = WatchApi.public("POST", "/auth/watch/code")
            val code = if (res.code == 200 && res.body != null) {
                try { JSONObject(res.body).optJSONObject("data")?.optString("code", "") } catch (e: Exception) { null }
            } else null
            if (code.isNullOrBlank()) {
                pairing = false
                runOnUiThread { if (!loadedOnce) view.setStatus(FocusView.Status.ERROR) }
                return@Thread
            }
            pairCode = code
            runOnUiThread {
                view.setPairing(code)
                handler.removeCallbacks(pollRunnable)
                handler.postDelayed(pollRunnable, 3000)
            }
        }.start()
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            val code = pairCode ?: return
            Thread {
                val res = WatchApi.public("GET", "/auth/watch/claim?code=$code")
                if (res.code == 200 && res.body != null) {
                    try {
                        val data = JSONObject(res.body).optJSONObject("data")
                        val st = data?.optString("status", "") ?: ""
                        val token = data?.optString("token", "") ?: ""
                        if (st == "linked" && token.isNotBlank()) {
                            WatchStore.saveToken(applicationContext, token)
                            pairing = false
                            pairCode = null
                            runOnUiThread { refresh(true) }
                            return@Thread
                        }
                        if (st == "expired") {
                            pairing = false
                            pairCode = null
                            runOnUiThread { startPairing() }
                            return@Thread
                        }
                    } catch (e: Exception) {
                    }
                }
                runOnUiThread { handler.postDelayed(self, 3000) }
            }.start()
        }
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
