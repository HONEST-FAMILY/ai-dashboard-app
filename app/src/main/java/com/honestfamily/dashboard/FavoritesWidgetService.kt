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

    private fun iconFor(path: String): Int = when (path.trimEnd('/')) {
        "/my/remaining" -> R.drawable.ic_fav_remaining
        "/projects" -> R.drawable.ic_fav_projects
        "/journals" -> R.drawable.ic_fav_journals
        "/insights" -> R.drawable.ic_fav_insights
        "/candidates" -> R.drawable.ic_fav_candidates
        "/mindmap" -> R.drawable.ic_fav_mindmap
        "/marketing/stats" -> R.drawable.ic_fav_marketing_stats
        "/sns-posts" -> R.drawable.ic_fav_sns_posts
        "/schedule" -> R.drawable.ic_fav_calendar
        "/travel" -> R.drawable.ic_fav_travel
        "/reminders" -> R.drawable.ic_fav_reminders
        "/library" -> R.drawable.ic_fav_library
        "/design-references" -> R.drawable.ic_fav_design_references
        "/routines" -> R.drawable.ic_fav_routines
        "/inspirations" -> R.drawable.ic_fav_inspirations
        "/memos" -> R.drawable.ic_fav_memos
        "/my/leaves" -> R.drawable.ic_fav_calendar_check
        "/leaves/calendar" -> R.drawable.ic_fav_calendar
        "/admin/leave-requests" -> R.drawable.ic_fav_calendar_check
        "/admin/leave-balances" -> R.drawable.ic_fav_calendar_check
        "/admin/holidays" -> R.drawable.ic_fav_calendar
        "/my/profile" -> R.drawable.ic_fav_profile
        "/admin/budget" -> R.drawable.ic_fav_budget
        "/admin/users" -> R.drawable.ic_fav_users
        "/admin/agents" -> R.drawable.ic_fav_agents
        "/admin/prompt-templates" -> R.drawable.ic_fav_templates
        "/admin/announcements" -> R.drawable.ic_fav_announcements
        "/admin/usage" -> R.drawable.ic_fav_usage
        else -> R.drawable.ic_fav_generic
    }

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val v = RemoteViews(context.packageName, R.layout.widget_favorites_item)
        v.setImageViewResource(R.id.fav_icon, iconFor(item.path))
        v.setTextViewText(R.id.fav_label, item.label)
        val target = if (item.path.startsWith("http")) item.path else AppConfig.BASE_WEB + item.path
        v.setOnClickFillInIntent(
            R.id.fav_root,
            Intent().putExtra(AppConfig.EXTRA_OPEN_URL, target)
        )
        return v
    }
}
