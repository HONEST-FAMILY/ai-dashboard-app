package com.honestfamily.dashboard

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

class FavoritesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FavoritesFactory(applicationContext)
}

private data class FavItem(val label: String, val path: String)

private class FavoritesFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private val items = ArrayList<FavItem>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items.clear()
        val token = AppConfig.savedToken(context) ?: return
        val body = Api.get("/auth/me", token) ?: return
        try {
            val data = JSONObject(body).optJSONObject("data") ?: return
            val prefs = data.optJSONObject("preferences") ?: return
            val favs = prefs.optJSONArray("favorites") ?: return
            for (i in 0 until favs.length()) {
                val o = favs.optJSONObject(i) ?: continue
                val path = o.optString("path", "")
                val label = o.optString("label", "")
                if (path.isNotBlank() && label.isNotBlank()) items.add(FavItem(label, path))
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    override fun onDestroy() {
        items.clear()
    }

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val v = RemoteViews(context.packageName, R.layout.widget_favorites_item)
        v.setTextViewText(R.id.fav_label, item.label)
        val target = if (item.path.startsWith("http")) item.path else AppConfig.BASE_WEB + item.path
        v.setOnClickFillInIntent(
            R.id.fav_root,
            Intent().putExtra(AppConfig.EXTRA_OPEN_URL, target)
        )
        return v
    }
}
