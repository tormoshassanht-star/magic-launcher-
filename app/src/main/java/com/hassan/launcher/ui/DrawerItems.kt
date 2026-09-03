package com.hassan.launcher.ui

import android.text.format.DateUtils
import com.hassan.launcher.R
import com.hassan.launcher.data.Prefs
import com.hassan.launcher.model.AppInfo
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SortMode(val label: String) {
    NAME("Name"),
    INSTALL_NEWEST("Newest installed"),
    INSTALL_OLDEST("Oldest installed"),
    UPDATED("Recently updated"),
    MOST_USED("Most used"),
    RECENT("Recently used");

    companion object {
        fun of(name: String): SortMode = entries.firstOrNull { it.name == name } ?: NAME
    }
}

sealed class DrawerItem {
    data class Header(val title: String) : DrawerItem()
    data class App(val app: AppInfo, val sub: String?) : DrawerItem()
    data class Action(val title: String, val icon: Int, val run: () -> Unit) : DrawerItem()
}

object DrawerListBuilder {
    private val nameComparator = compareBy<AppInfo>({ it.sortLetter == '#' }, { it.label.lowercase() })

    fun build(
        apps: List<AppInfo>,
        mode: SortMode,
        query: String,
        prefs: Prefs,
        onWeb: (String) -> Unit,
        onStore: (String) -> Unit,
    ): List<DrawerItem> {
        val hidden = prefs.hidden
        val visible = apps.filter { it.key !in hidden }
        val q = query.trim()
        if (q.isNotEmpty()) {
            val matched = visible
                .filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
                .sortedWith(compareBy<AppInfo> { !it.label.startsWith(q, true) }.then(nameComparator))
            val out = ArrayList<DrawerItem>()
            matched.mapTo(out) { DrawerItem.App(it, null) }
            out += DrawerItem.Action("Search the web for \"$q\"", R.drawable.ic_search) { onWeb(q) }
            out += DrawerItem.Action("Search Play Store for \"$q\"", R.drawable.ic_store) { onStore(q) }
            return out
        }
        return when (mode) {
            SortMode.NAME -> visible.sortedWith(nameComparator).map { DrawerItem.App(it, null) }
            SortMode.INSTALL_NEWEST -> grouped(visible.sortedByDescending { it.installTime }) { it.installTime }
            SortMode.INSTALL_OLDEST -> grouped(visible.sortedBy { it.installTime }) { it.installTime }
            SortMode.UPDATED -> grouped(visible.sortedByDescending { it.updateTime }) { it.updateTime }
            SortMode.MOST_USED -> {
                val used = visible.filter { prefs.launchCount(it.key) > 0 }
                    .sortedWith(compareByDescending<AppInfo> { prefs.launchCount(it.key) }.then(nameComparator))
                val rest = visible.filter { prefs.launchCount(it.key) == 0 }.sortedWith(nameComparator)
                val out = ArrayList<DrawerItem>()
                if (used.isNotEmpty()) {
                    out += DrawerItem.Header("Most used")
                    used.mapTo(out) {
                        val n = prefs.launchCount(it.key)
                        DrawerItem.App(it, if (n == 1) "1 launch" else "$n launches")
                    }
                }
                if (rest.isNotEmpty()) {
                    out += DrawerItem.Header("Not opened yet")
                    rest.mapTo(out) { DrawerItem.App(it, null) }
                }
                out
            }
            SortMode.RECENT -> {
                val now = System.currentTimeMillis()
                val used = visible.filter { prefs.lastUsed(it.key) > 0 }.sortedByDescending { prefs.lastUsed(it.key) }
                val rest = visible.filter { prefs.lastUsed(it.key) == 0L }.sortedWith(nameComparator)
                val out = ArrayList<DrawerItem>()
                if (used.isNotEmpty()) {
                    out += DrawerItem.Header("Recently used")
                    used.mapTo(out) {
                        val rel = DateUtils.getRelativeTimeSpanString(
                            prefs.lastUsed(it.key), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                        ).toString()
                        DrawerItem.App(it, rel)
                    }
                }
                if (rest.isNotEmpty()) {
                    out += DrawerItem.Header("Not opened yet")
                    rest.mapTo(out) { DrawerItem.App(it, null) }
                }
                out
            }
        }
    }

    private fun grouped(list: List<AppInfo>, time: (AppInfo) -> Long): List<DrawerItem> {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 86_400_000L
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val out = ArrayList<DrawerItem>()
        var last: String? = null
        for (app in list) {
            val t = time(app)
            val group = when {
                t >= startOfToday -> "Today"
                t >= startOfToday - day -> "Yesterday"
                t >= startOfToday - 6 * day -> "This week"
                t >= startOfToday - 29 * day -> "This month"
                else -> monthFmt.format(Date(t))
            }
            if (group != last) {
                out += DrawerItem.Header(group)
                last = group
            }
            out += DrawerItem.App(app, dateFmt.format(Date(t)))
        }
        return out
    }
}
