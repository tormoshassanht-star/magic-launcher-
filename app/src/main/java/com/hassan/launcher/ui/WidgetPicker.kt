package com.hassan.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hassan.launcher.databinding.ItemWidgetBinding
import com.hassan.launcher.databinding.ItemWidgetHeaderBinding
import com.hassan.launcher.databinding.SheetWidgetsBinding
import kotlin.math.ceil

object WidgetPicker {

    private sealed class Row {
        class Header(val label: String, val pkg: String) : Row()
        class Widget(val info: AppWidgetProviderInfo, val appLabel: String) : Row()
    }

    fun show(
        context: Context,
        cellWpx: Float,
        cellHpx: Float,
        packageFilter: String? = null,
        onPick: (AppWidgetProviderInfo) -> Unit,
    ) {
        val pm = context.packageManager
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .filter { packageFilter == null || it.provider.packageName == packageFilter }
        val groups = providers.groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                Triple(label, pkg, list.sortedBy { it.loadLabel(pm) })
            }
            .sortedBy { it.first.lowercase() }

        fun rowsFor(query: String): List<Row> {
            val q = query.trim().lowercase()
            val rows = ArrayList<Row>()
            for ((label, pkg, list) in groups) {
                val matching = if (q.isEmpty()) list else list.filter {
                    label.lowercase().contains(q) || it.loadLabel(pm).lowercase().contains(q)
                }
                if (matching.isEmpty()) continue
                rows += Row.Header(label, pkg)
                matching.mapTo(rows) { Row.Widget(it, label) }
            }
            return rows
        }

        var rows = rowsFor("")
        val dialog = BottomSheetDialog(context)
        val b = SheetWidgetsBinding.inflate(LayoutInflater.from(context))
        if (packageFilter != null) b.sheetTitle.text = groups.firstOrNull()?.first?.let { "$it widgets" } ?: "Widgets"
        b.widgetList.layoutManager = LinearLayoutManager(context)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inf = LayoutInflater.from(parent.context)
                return if (viewType == 0) HeaderVH(ItemWidgetHeaderBinding.inflate(inf, parent, false))
                else WidgetVH(ItemWidgetBinding.inflate(inf, parent, false))
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val row = rows[position]) {
                    is Row.Header -> {
                        val h = holder as HeaderVH
                        h.b.appName.text = row.label
                        h.b.appIcon.setImageDrawable(try { pm.getApplicationIcon(row.pkg) } catch (e: Exception) { null })
                    }
                    is Row.Widget -> {
                        val w = holder as WidgetVH
                        val info = row.info
                        w.b.widgetLabel.text = info.loadLabel(pm)
                        val sx = ceil(info.minWidth / cellWpx).toInt().coerceAtLeast(1)
                        val sy = ceil(info.minHeight / cellHpx).toInt().coerceAtLeast(1)
                        w.b.widgetSize.text = "$sx × $sy"
                        val preview = try { info.loadPreviewImage(context, 0) } catch (e: Exception) { null }
                            ?: try { info.loadIcon(context, 0) } catch (e: Exception) { null }
                        w.b.widgetPreview.setImageDrawable(preview)
                        w.b.root.setOnClickListener {
                            dialog.dismiss()
                            onPick(info)
                        }
                    }
                }
            }
        }
        b.widgetList.adapter = adapter
        b.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.widgetSearch.doAfterTextChanged {
            rows = rowsFor(it?.toString() ?: "")
            adapter.notifyDataSetChanged()
            b.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
        dialog.setContentView(b.root)
        dialog.behavior.peekHeight = context.resources.displayMetrics.heightPixels * 3 / 4
        dialog.show()
    }

    private class HeaderVH(val b: ItemWidgetHeaderBinding) : RecyclerView.ViewHolder(b.root)
    private class WidgetVH(val b: ItemWidgetBinding) : RecyclerView.ViewHolder(b.root)
}
