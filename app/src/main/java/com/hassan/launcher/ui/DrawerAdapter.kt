package com.hassan.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.R
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.databinding.ItemDrawerActionBinding
import com.hassan.launcher.databinding.ItemDrawerAppBinding
import com.hassan.launcher.databinding.ItemDrawerHeaderBinding
import com.hassan.launcher.model.AppInfo

class DrawerAdapter(
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<DrawerItem> = emptyList()
        private set
    var textColor = Color.BLACK
    var subColor = Color.GRAY
    var showNewBadge = true
    var selectionMode = false
        private set
    val selected = LinkedHashSet<String>()

    private val newWindowMs = 48L * 60 * 60 * 1000

    fun submit(list: List<DrawerItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun toggle(app: AppInfo) {
        if (!selected.remove(app.key)) selected.add(app.key)
        val idx = items.indexOfFirst { it is DrawerItem.App && it.app.key == app.key }
        if (idx >= 0) notifyItemChanged(idx) else notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedApps(): List<AppInfo> =
        items.filterIsInstance<DrawerItem.App>().map { it.app }.filter { it.key in selected }.distinctBy { it.key }

    fun firstApp(): AppInfo? = (items.firstOrNull { it is DrawerItem.App } as? DrawerItem.App)?.app

    fun isApp(position: Int) = items[position] is DrawerItem.App

    fun positionForLetter(c: Char): Int = items.indexOfFirst { it is DrawerItem.App && it.app.sortLetter == c }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is DrawerItem.Header -> 0
        is DrawerItem.App -> 1
        is DrawerItem.Action -> 2
    }

    class HeaderVH(val b: ItemDrawerHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class AppVH(val b: ItemDrawerAppBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemDrawerActionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemDrawerHeaderBinding.inflate(inf, parent, false))
            1 -> AppVH(ItemDrawerAppBinding.inflate(inf, parent, false)).also { vh ->
                val s = IconCache.sizePx
                vh.b.icon.layoutParams = vh.b.icon.layoutParams.apply { width = s; height = s }
            }
            else -> ActionVH(ItemDrawerActionBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DrawerItem.Header -> {
                val h = holder as HeaderVH
                h.b.headerText.text = item.title
                h.b.headerText.setTextColor(subColor)
            }
            is DrawerItem.App -> bindApp(holder as AppVH, item)
            is DrawerItem.Action -> {
                val a = holder as ActionVH
                a.b.actionText.text = item.title
                a.b.actionText.setTextColor(textColor)
                a.b.actionIcon.setImageResource(item.icon)
                a.b.actionIcon.imageTintList = ColorStateList.valueOf(subColor)
                a.b.root.setOnClickListener { item.run() }
            }
        }
    }

    private fun bindApp(vh: AppVH, item: DrawerItem.App) {
        val app = item.app
        val ctx = vh.itemView.context
        vh.b.icon.setImageBitmap(IconCache.get(ctx, app))
        vh.b.label.text = app.label
        vh.b.label.setTextColor(textColor)
        vh.b.sub.text = item.sub
        vh.b.sub.isVisible = item.sub != null
        vh.b.sub.setTextColor(subColor)
        vh.b.badge.isVisible = showNewBadge && System.currentTimeMillis() - app.installTime < newWindowMs
        val isSelected = selectionMode && app.key in selected
        vh.b.check.isVisible = isSelected
        vh.b.check.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent))
        vh.b.icon.alpha = if (selectionMode && !isSelected) 0.55f else 1f
        vh.b.root.setOnClickListener {
            if (selectionMode) toggle(app) else onClick(app, vh.b.icon)
        }
        vh.b.root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (selectionMode) toggle(app) else onLongClick(app, vh.b.icon)
            true
        }
    }
}
