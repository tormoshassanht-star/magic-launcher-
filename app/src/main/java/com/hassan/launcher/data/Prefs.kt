package com.hassan.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class FolderData(var name: String, val apps: MutableList<String>)

class HomeCell(val key: String, var col: Int, var row: Int, var spanX: Int = 1, var spanY: Int = 1)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val usage: SharedPreferences = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    val columns get() = sp.getString("columns", "4")!!.toInt()
    val rows get() = sp.getString("rows", "6")!!.toInt()
    val drawerColumns get() = sp.getString("drawer_columns", "4")!!.toInt()
    val iconShape get() = sp.getString("icon_shape", "rounded")!!
    val iconSize get() = sp.getInt("icon_size", 100)
    val showHomeLabels get() = sp.getBoolean("home_labels", true)
    val showClock get() = sp.getBoolean("show_clock", false)
    val dockEnabled get() = sp.getBoolean("dock_enabled", true)
    val newBadge get() = sp.getBoolean("new_badge", true)
    val doubleTapLock get() = sp.getBoolean("double_tap_lock", true)
    val swipeDownNotifications get() = sp.getBoolean("swipe_down_notifications", true)
    val launcherNotifPanel get() = sp.getString("notif_panel", "launcher") == "launcher"
    val drawerTheme get() = sp.getString("drawer_theme", "auto")!!
    val drawerStyle get() = sp.getString("drawer_style", "paged")!!
    val searchStyle get() = sp.getString("search_style", "button")!!
    val searchTarget get() = sp.getString("search_target", "system")!!
    val badges get() = sp.getBoolean("badges", true)

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var skippedUpdate: String
        get() = sp.getString("skipped_update", "")!!
        set(v) = sp.edit().putString("skipped_update", v).apply()

    var hideDefaultBanner: Boolean
        get() = sp.getBoolean("hide_default_banner", false)
        set(v) = sp.edit().putBoolean("hide_default_banner", v).apply()

    var askedBattery: Boolean
        get() = sp.getBoolean("asked_battery", false)
        set(v) = sp.edit().putBoolean("asked_battery", v).apply()

    var launchCount: Int
        get() = sp.getInt("launch_count", 0)
        set(v) = sp.edit().putInt("launch_count", v).apply()

    var askedBadges: Boolean
        get() = sp.getBoolean("asked_badges", false)
        set(v) = sp.edit().putBoolean("asked_badges", v).apply()

    var homePage: Int
        get() = sp.getInt("home_page", 0)
        set(v) = sp.edit().putInt("home_page", v).apply()

    var askedDefault: Boolean
        get() = sp.getBoolean("asked_default", false)
        set(v) = sp.edit().putBoolean("asked_default", v).apply()

    var drawerSort: String
        get() = sp.getString("drawer_sort", "NAME")!!
        set(v) = sp.edit().putString("drawer_sort", v).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v.toSet()).apply()

    var notifHidden: Set<String>
        get() = sp.getStringSet("notif_hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("notif_hidden", v.toSet()).apply()

    fun configSignature() = listOf(
        columns, rows, drawerColumns, iconShape, iconSize, showHomeLabels,
        showClock, dockEnabled, newBadge, drawerTheme, drawerStyle, searchStyle, badges,
    ).joinToString("|")

    fun recordLaunch(key: String) {
        usage.edit()
            .putInt("count:$key", usage.getInt("count:$key", 0) + 1)
            .putLong("last:$key", System.currentTimeMillis())
            .apply()
    }

    fun launchCount(key: String) = usage.getInt("count:$key", 0)
    fun lastUsed(key: String) = usage.getLong("last:$key", 0L)

    val hasLayout get() = sp.contains("pages")

    var pages: MutableList<MutableList<HomeCell>>
        get() {
            val arr = JSONArray(sp.getString("pages", "[[]]"))
            return MutableList(arr.length()) { i ->
                val p = arr.getJSONArray(i)
                MutableList(p.length()) { j ->
                    when (val el = p.get(j)) {
                        is JSONObject -> HomeCell(
                            el.getString("k"), el.optInt("c", -1), el.optInt("r", -1),
                            el.optInt("w", 1), el.optInt("h", 1),
                        )
                        else -> HomeCell(el.toString(), -1, -1)
                    }
                }
            }
        }
        set(v) {
            val arr = JSONArray()
            for (page in v) {
                val p = JSONArray()
                for (c in page) {
                    p.put(JSONObject().put("k", c.key).put("c", c.col).put("r", c.row).put("w", c.spanX).put("h", c.spanY))
                }
                arr.put(p)
            }
            sp.edit().putString("pages", arr.toString()).apply()
        }

    var dock: MutableList<String>
        get() {
            val arr = JSONArray(sp.getString("dock", "[]"))
            return MutableList(arr.length()) { arr.getString(it) }
        }
        set(v) = sp.edit().putString("dock", JSONArray(v).toString()).apply()

    var folders: MutableMap<String, FolderData>
        get() {
            val obj = JSONObject(sp.getString("folders", "{}"))
            val out = LinkedHashMap<String, FolderData>()
            for (id in obj.keys()) {
                val f = obj.getJSONObject(id)
                val arr = f.getJSONArray("apps")
                out[id] = FolderData(f.optString("name", "Folder"), MutableList(arr.length()) { arr.getString(it) })
            }
            return out
        }
        set(v) {
            val obj = JSONObject()
            for ((id, f) in v) obj.put(id, JSONObject().put("name", f.name).put("apps", JSONArray(f.apps)))
            sp.edit().putString("folders", obj.toString()).apply()
        }

    fun resetLayout() = sp.edit().remove("pages").remove("dock").remove("folders").apply()
}
