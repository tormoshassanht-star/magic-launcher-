package com.hassan.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
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
        class Widget(val info: AppWidgetProviderInfo) : Row()
    }

    fun show(context: Context, cellWpx: Float, cellHpx: Float, onPick: (AppWidgetProviderInfo) -> Unit) {
        val pm = context.packageManager
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
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
        val rows = ArrayList<Row>()
        for ((label, pkg, list) in groups) {
            rows += Row.Header(label, pkg)
            list.mapTo(rows) { Row.Widget(it) }
        }

        val dialog = BottomSheetDialog(context)
        val b = SheetWidgetsBinding.inflate(LayoutInflater.from(context))
        b.widgetList.layoutManager = LinearLayoutManager(context)
        b.widgetList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
        b.emptyText.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        dialog.setContentView(b.root)
        dialog.behavior.peekHeight = context.resources.displayMetrics.heightPixels * 2 / 3
        dialog.show()
    }

    private class HeaderVH(val b: ItemWidgetHeaderBinding) : RecyclerView.ViewHolder(b.root)
    private class WidgetVH(val b: ItemWidgetBinding) : RecyclerView.ViewHolder(b.root)
}
