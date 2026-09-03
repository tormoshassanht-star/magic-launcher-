package com.hassan.launcher.data

import android.content.Context
import com.hassan.launcher.R
import com.hassan.launcher.model.AppInfo
import org.json.JSONArray
import org.json.JSONObject

class PresetResult(
    val pages: MutableList<MutableList<String>>,
    val dock: MutableList<String>,
    val folders: MutableMap<String, FolderData>,
    val unmatched: List<String>,
)

object LayoutPreset {
    fun load(context: Context): JSONObject =
        JSONObject(context.resources.openRawResource(R.raw.layout_preset).bufferedReader().readText())

    fun isEmpty(context: Context): Boolean {
        val json = load(context)
        return json.optJSONArray("pages")?.length() ?: 0 == 0 && json.optJSONArray("dock")?.length() ?: 0 == 0
    }

    fun apply(context: Context, apps: List<AppInfo>): PresetResult {
        val json = load(context)
        val unmatched = ArrayList<String>()
        val used = HashSet<String>()
        val byNorm = apps.groupBy { norm(it.label) }

        fun match(label: String): String? {
            val n = norm(label)
            val candidates = byNorm[n]
                ?: apps.filter { norm(it.label).startsWith(n) }.ifEmpty { apps.filter { norm(it.label).contains(n) || n.contains(norm(it.label)) && norm(it.label).length >= 4 } }
            if (candidates.isEmpty()) {
                unmatched += label
                return null
            }
            val pick = candidates.firstOrNull { it.key !in used } ?: return null
            used += pick.key
            return pick.key
        }

        val folders = LinkedHashMap<String, FolderData>()
        var folderSeq = 0

        fun entry(v: Any?): String? = when (v) {
            is String -> match(v)
            is JSONObject -> {
                val names = v.optJSONArray("apps") ?: JSONArray()
                val keys = (0 until names.length()).mapNotNull { match(names.getString(it)) }.toMutableList()
                when (keys.size) {
                    0 -> null
                    1 -> keys[0]
                    else -> {
                        val id = "preset${folderSeq++}"
                        folders[id] = FolderData(v.optString("folder", "Folder"), keys)
                        "folder:$id"
                    }
                }
            }
            else -> null
        }

        val dockArr = json.optJSONArray("dock") ?: JSONArray()
        val dock = (0 until dockArr.length()).mapNotNull { entry(dockArr.get(it)) }.toMutableList()
        val pagesArr = json.optJSONArray("pages") ?: JSONArray()
        val pages = (0 until pagesArr.length()).map { p ->
            val page = pagesArr.getJSONArray(p)
            (0 until page.length()).mapNotNull { entry(page.get(it)) }.toMutableList()
        }.toMutableList()
        while (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.size - 1)
        if (pages.isEmpty()) pages.add(mutableListOf())
        return PresetResult(pages, dock, folders, unmatched)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
